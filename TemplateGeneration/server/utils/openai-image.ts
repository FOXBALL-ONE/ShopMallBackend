import {createError} from 'h3'

export type ImageReference = {
  storageKey: string
  originalName: string
  contentType: string
  data: Buffer
  role: string
  instruction: string
}

export type ImageGenerationRoute = {
  baseUrl: string
  type: string
  auth: string
  credentialValue: string
  model: string
}

export type ImageGenerationInput = {
  prompt: string
  negativePrompt: string
  size: string
  quality: string
  background: string
  outputFormat: string
  references: ImageReference[]
  timeoutMs?: number
}

export type GeneratedImage = {
  data: Buffer
  contentType: string
  upstreamRequestId: string | null
}

function endpoint(baseUrl: string, resource: 'generations' | 'edits') {
  const parsed = new URL(baseUrl)
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') throw createError({statusCode: 400, statusMessage: '模型提供商基础地址必须使用 HTTP 或 HTTPS。'})
  const pathname = parsed.pathname.replace(/\/+$/, '')
  // Keep POST requests on the canonical no-slash path. Some compatible
  // gateways redirect a trailing-slash multipart request and drop its body.
  parsed.pathname = `${pathname.endsWith('/v1') ? pathname : `${pathname}/v1`}/images/${resource}`
  parsed.search = ''
  parsed.hash = ''
  return parsed.toString()
}

function headers(route: ImageGenerationRoute) {
  const result: Record<string, string> = {Accept: 'application/json'}
  if (route.credentialValue && route.auth !== '无需认证') {
    if (route.auth === 'Custom Header') result['x-api-key'] = route.credentialValue
    else result.Authorization = `Bearer ${route.credentialValue}`
  }
  return result
}

function composePrompt(input: ImageGenerationInput) {
  const prompt = input.prompt.trim()
  const negativePrompt = input.negativePrompt.trim()
  if (!input.references.length) return [prompt, negativePrompt ? `排除项：${negativePrompt}` : ''].filter(Boolean).join('\n\n').slice(0, 8000)

  // Keep every reference instruction represented while leaving the primary
  // prompt first. This fits the edits endpoint's 1000-character limit even
  // when a workflow contains the maximum number of references.
  const referenceBudget = Math.max(50, Math.floor(520 / input.references.length))
  const references = input.references.map((reference, index) => {
    const role = reference.role.trim().slice(0, 20) || 'reference'
    const instruction = reference.instruction.trim() || '请参考该图片的主体特征、构图和风格。'
    return `参考图 ${index + 1}（${role}）：${instruction}`.slice(0, referenceBudget)
  }).join('\n')
  const negative = negativePrompt ? `排除项：${negativePrompt.slice(0, 120)}` : ''
  const suffix = [references, negative].filter(Boolean).join('\n\n')
  const promptBudget = Math.max(0, 1000 - suffix.length - (suffix ? 2 : 0))
  return [prompt.slice(0, promptBudget), suffix].filter(Boolean).join('\n\n').slice(0, 1000)
}

function decodeBase64(value: string) {
  try {
    const encoded = value.includes(',') ? value.slice(value.indexOf(',') + 1) : value
    const data = Buffer.from(encoded.trim(), 'base64')
    if (!data.length) throw new Error('empty')
    if (data.length > 50 * 1024 * 1024) throw new Error('too-large')
    return data
  } catch {
    throw createError({statusCode: 502, statusMessage: '图像生成服务返回的 Base64 图片无效或超过 50 MB 限制。'})
  }
}

function normalizeEditSize(value: string) {
  const normalized = value.trim() || '1024x1024'
  if (normalized === 'auto') return '1024x1024'
  if (!['256x256', '512x512', '1024x1024', '1024x1536', '1536x1024', '2048x2048', '2048x1152', '1152x2048', '3840x2160', '2160x3840'].includes(normalized)) {
    throw createError({statusCode: 400, statusMessage: '图生图输出尺寸必须是 256x256、512x512、1024x1024、1024x1536、1536x1024、2048x2048、2048x1152、1152x2048、3840x2160 或 2160x3840。'})
  }
  return normalized
}

function detectImageContentType(data: Buffer, fallback: string) {
  if (data.length >= 8 && data.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'image/png'
  if (data.length >= 3 && data.subarray(0, 3).equals(Buffer.from([0xff, 0xd8, 0xff]))) return 'image/jpeg'
  if (data.length >= 12 && data.subarray(0, 4).toString('ascii') === 'RIFF' && data.subarray(8, 12).toString('ascii') === 'WEBP') return 'image/webp'
  return fallback
}

async function readJson(response: Response) {
  try { return await response.json() as unknown } catch { throw createError({statusCode: 502, statusMessage: '图像生成服务响应不是有效的 JSON。'}) }
}

function upstreamError(status: number, payload: unknown) {
  const message = payload && typeof payload === 'object' && 'error' in payload && payload.error && typeof payload.error === 'object' && 'message' in payload.error && typeof payload.error.message === 'string'
    ? payload.error.message
    : `上游图像服务返回 HTTP ${status}。`
  const error = createError({statusCode: status === 429 ? 429 : status >= 500 ? 502 : 400, statusMessage: message.slice(0, 500)})
  ;(error as unknown as {upstreamStatus?: number; retryable?: boolean}).upstreamStatus = status
  ;(error as unknown as {retryable?: boolean}).retryable = status === 429 || status >= 500
  throw error
}

export async function generateImage(route: ImageGenerationRoute, input: ImageGenerationInput): Promise<GeneratedImage> {
  if (route.type !== 'OpenAI' && route.type !== '兼容网关') throw createError({statusCode: 400, statusMessage: '当前提供商不支持 OpenAI 图像协议。'})
  const requestHeaders = headers(route)
  const prompt = composePrompt(input)
  const timeoutMs = Math.max(1000, Math.min(900000, input.timeoutMs ?? Number(process.env.TEMPLATE_GENERATION_TIMEOUT_MS || 300000)))
  let response: Response
  if (input.references.length) {
    const form = new FormData()
    const size = normalizeEditSize(input.size)
    form.append('model', route.model)
    form.append('prompt', prompt)
    form.append('n', '1')
    form.append('size', size)
    form.append('response_format', 'b64_json')
    // Apifox documents the singular `image` field. NewAPI normalizes repeated
    // `image[]` fields for multi-reference requests, so use the documented
    // shape for one image and the compatibility shape for multiple images.
    const imageField = input.references.length === 1 ? 'image' : 'image[]'
    input.references.forEach((reference) => form.append(imageField, new Blob([new Uint8Array(reference.data)], {type: reference.contentType}), reference.originalName || 'reference-image'))
    try { response = await fetch(endpoint(route.baseUrl, 'edits'), {method: 'POST', headers: requestHeaders, body: form, signal: AbortSignal.timeout(timeoutMs)}) }
    catch { throw createError({statusCode: 504, statusMessage: '图像生成请求超时或网络连接失败。'}) }
  } else {
    const body = {model: route.model, prompt, n: 1, size: input.size, quality: input.quality, background: input.background, output_format: input.outputFormat}
    try { response = await fetch(endpoint(route.baseUrl, 'generations'), {method: 'POST', headers: {...requestHeaders, 'Content-Type': 'application/json'}, body: JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs)}) }
    catch { throw createError({statusCode: 504, statusMessage: '图像生成请求超时或网络连接失败。'}) }
  }
  const payload = await readJson(response)
  if (!response.ok) upstreamError(response.status, payload)
  const source = payload && typeof payload === 'object' && 'data' in payload && Array.isArray(payload.data) ? payload.data[0] : null
  if (!source || typeof source !== 'object') throw createError({statusCode: 502, statusMessage: '图像生成服务响应缺少 data 图片数据。'})
  const item = source as Record<string, unknown>
  const upstreamRequestId = response.headers.get('x-request-id') || response.headers.get('request-id') || (typeof payload === 'object' && payload && 'id' in payload && typeof payload.id === 'string' ? payload.id : null)
  if (typeof item.b64_json === 'string') {
    const data = decodeBase64(item.b64_json)
    const fallback = input.references.length ? 'image/png' : input.outputFormat === 'webp' ? 'image/webp' : input.outputFormat === 'jpeg' ? 'image/jpeg' : 'image/png'
    return {data, contentType: detectImageContentType(data, fallback), upstreamRequestId}
  }
  if (typeof item.url === 'string') {
    let imageResponse: Response
    try { imageResponse = await fetch(item.url, {signal: AbortSignal.timeout(timeoutMs)}) } catch { throw createError({statusCode: 502, statusMessage: '无法下载图像生成服务返回的图片。'}) }
    if (!imageResponse.ok) throw createError({statusCode: 502, statusMessage: '图像生成服务返回的图片下载失败。'})
    const rawContentType = imageResponse.headers.get('content-type')?.split(';')[0] || 'image/png'
    const contentType = rawContentType === 'image/jpg' ? 'image/jpeg' : rawContentType
    const data = Buffer.from(await imageResponse.arrayBuffer())
    if (data.length > 50 * 1024 * 1024) throw createError({statusCode: 502, statusMessage: '图像生成服务返回的图片超过 50 MB 限制。'})
    return {data, contentType: detectImageContentType(data, contentType), upstreamRequestId}
  }
  throw createError({statusCode: 502, statusMessage: '图像生成服务响应中没有 b64_json 或 url。'})
}
