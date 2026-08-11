function containsUnsafeCharacters(value: string) {
  return /[\\\u0000-\u001F\u007F]/.test(value)
}

function hasUnsafeDecodedPath(path: string) {
  let decoded = path
  for (let count = 0; count < 3; count += 1) {
    if (containsUnsafeCharacters(decoded) || decoded.startsWith('//')) return true
    try {
      const next = decodeURIComponent(decoded)
      if (next === decoded) return false
      decoded = next
    } catch {
      return true
    }
  }
  return containsUnsafeCharacters(decoded) || decoded.startsWith('//')
}

export function isSafeInternalAnnouncementActionUrl(url: string | null | undefined) {
  if (!url || containsUnsafeCharacters(url) || !url.startsWith('/') || url.startsWith('//')) return false
  const path = url.split(/[?#]/, 1)[0] || '/'
  return !hasUnsafeDecodedPath(path)
}

export function isSafeHttpsAnnouncementActionUrl(url: string | null | undefined) {
  if (!url || containsUnsafeCharacters(url)) return false
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' && Boolean(parsed.hostname) && !parsed.username && !parsed.password &&
      !hasUnsafeDecodedPath(parsed.pathname)
  } catch {
    return false
  }
}

export function isSafeAnnouncementActionUrl(url: string | null | undefined) {
  return isSafeInternalAnnouncementActionUrl(url) || isSafeHttpsAnnouncementActionUrl(url)
}
