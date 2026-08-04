import type { ApiResult } from "~/types/http";
import type {
    BikiniSuitResponse,
    BikiniSuitUpsertRequest,
    CoverUpResponse,
    CoverUpUpsertRequest,
    DressResponse,
    DressUpsertRequest,
    FileMetadataResponse,
    OnePieceSuitResponse,
    OnePieceSuitUpsertRequest,
    ProductDeleteResponse,
    ProductType,
    Tag,
} from "~/types/product";
import { ofetch } from "ofetch";

/**
 * 品类配置：type → 路径 / 外层 key。
 * 后端 4 品类接口同构，仅资源路径与外层 key 不同，故集中在此表驱动。
 */
export interface CategoryConfig {
    type: ProductType;
    label: string;
    basePath: string;
    singularKey: string;
    pluralKey: string;
}

export const CATEGORIES: readonly CategoryConfig[] = [
    { type: "DRESS", label: "连衣裙", basePath: "/dresses", singularKey: "dress", pluralKey: "dresses" },
    { type: "BIKINI", label: "比基尼", basePath: "/bikini-suits", singularKey: "bikiniSuit", pluralKey: "bikiniSuits" },
    { type: "ONE_PIECE", label: "一件式", basePath: "/one-piece-suits", singularKey: "onePieceSuit", pluralKey: "onePieceSuits" },
    { type: "COVER_UP", label: "罩衫", basePath: "/cover-ups", singularKey: "coverUp", pluralKey: "coverUps" },
] as const;

/**
 * 取品类配置。传入 ProductType 返回对应的路径与外层 key。
 * 供本 composable 内部及页面/组件复用。
 */
export function getCategoryConfig(type: ProductType): CategoryConfig {
    const cfg = CATEGORIES.find((c) => c.type === type);
    if (!cfg) {
        throw new Error(`未知的商品品类: ${type}`);
    }
    return cfg;
}

/** 上传文件响应（data 内层）。 */
interface FileUploadResponse {
    files: FileMetadataResponse[];
}

/** 标签列表响应（data 内层）。 */
interface TagListResponse {
    tags: Tag[];
}

/** 401 状态码，与 useHttp 对齐。 */
const UNAUTHORIZED_STATUS = 401;

/**
 * 商品管理 API 封装。
 *
 * 基于 useHttp（已剥掉 ApiResult 外层，直接给 data）。
 * delete 是保留字，需对象解构: const { delete: del } = useHttp(adminApiBase)。
 *
 * 文件上传走 multipart/form-data，useHttp 的 post 系列默认 payloadMode
 * 不便传 FormData（query/json 分支会误把 FormData 当 query 或 body 处理），
 * 故此处对 /files 单独用 ofetch 直接发起请求，手动注入 Bearer token
 * （cookie admin_auth_token），与 useHttp 保持一致的 baseURL 与鉴权来源。
 */
export const useProductApi = () => {
    const runtimeConfig = useRuntimeConfig();
    const adminApiBase = (runtimeConfig.public.adminApiBase as string) || "http://127.0.0.1:8080/admin/api";
    const { get, post, put, delete: del } = useHttp(adminApiBase);
    const apiBase = (runtimeConfig.public.apiBase as string) || "http://127.0.0.1:8080/api";
    const authToken = useCookie<string | null>("admin_auth_token");
    const authUser = useCookie<unknown | null>("admin_user_info");

    /** 构造 Bearer 头，复用 useHttp 的归一逻辑（容忍已带 Bearer 前缀）。 */
    function authHeader(): Record<string, string> {
        const raw = authToken.value?.trim();
        if (!raw) {
            return {};
        }
        const value = /^bearer\s+/i.test(raw) ? raw : `Bearer ${raw}`;
        return { Authorization: value };
    }

    /**
     * 图片上传走 ofetch 绕过 useHttp，需自行处理 401：
     * 与 useHttp.handleUnauthorized 对齐——清登录态并跳 /login。
     */
    function handleSessionExpired() {
        authToken.value = null;
        authUser.value = null;
        if (import.meta.server) {
            throw createError({ statusCode: 401, statusMessage: "Unauthorized" });
        } else if (useRoute().path !== "/login") {
            navigateTo("/login");
        }
    }

    /** 取某品类的商品列表（全量，含下架/软删除）。 */
    function list<T extends ProductType>(category: T): Promise<ProductListByType<T>>;
    function list(category: ProductType): Promise<ProductListItemUnion[]> {
        const cfg = getCategoryConfig(category);
        // 外层 key 因品类而异，用 Record<string, unknown> 接收后取值；
        // 后端 admin 列表返回具体子类型（如 DressResponse），不写 productType 鉴别字段，
        // 此处按品类补注入，供前端 buildUpsertRequest/toggleStatus/批量操作分发品类使用。
        return get<Record<string, ProductListItemUnion[]>>(cfg.basePath).then(
            (data) =>
                ((data?.[cfg.pluralKey] as ProductListItemUnion[] | undefined) ?? []).map((item) => ({
                    ...item,
                    productType: cfg.type,
                })) as ProductListItemUnion[],
        );
    }

    /** 取某品类单个商品详情（返回解包后的实体，与 list/create/update 保持一致）。 */
    function getOne<T extends ProductType>(category: T, id: number): Promise<ResponseByType<T>>;
    function getOne(category: ProductType, id: number): Promise<unknown> {
        const cfg = getCategoryConfig(category);
        return get<Record<string, unknown>>(`${cfg.basePath}/${id}`).then(
            (data) => (data ?? {})[cfg.singularKey],
        );
    }

    /** 新增商品。payload 为对应品类 UpsertRequest。 */
    function create<T extends ProductType>(category: T, payload: UpsertRequestByType<T>): Promise<ResponseByType<T>>;
    function create(category: ProductType, payload: unknown): Promise<unknown> {
        const cfg = getCategoryConfig(category);
        // post 默认 payloadMode 由 useHttp 内部处理为 query，JSON 体需显式 json
        return post<unknown, unknown>(cfg.basePath, payload as Record<string, unknown>, {
            payloadMode: "json",
        }).then((data) => (data as Record<string, unknown>)?.[cfg.singularKey]);
    }

    /** 整体更新商品（需先取详情合并再 PUT）。 */
    function update<T extends ProductType>(
        category: T,
        id: number,
        payload: UpsertRequestByType<T>,
    ): Promise<ResponseByType<T>>;
    function update(category: ProductType, id: number, payload: unknown): Promise<unknown> {
        const cfg = getCategoryConfig(category);
        return put<unknown, unknown>(`${cfg.basePath}/${id}`, payload as Record<string, unknown>, {
            payloadMode: "json",
        }).then((data) => (data as Record<string, unknown>)?.[cfg.singularKey]);
    }

    /** 软删除商品（status=DELETED）。 */
    function remove(category: ProductType, id: number): Promise<ProductDeleteResponse> {
        const cfg = getCategoryConfig(category);
        return del<ProductDeleteResponse>(`${cfg.basePath}/${id}`);
    }

    /** 取标签全量列表。 */
    function listTags(): Promise<Tag[]> {
        return get<TagListResponse>("/tags").then((data) => data?.tags ?? []);
    }

    /**
     * 上传图片（multipart/form-data，字段名 files，可多文件，≤20）。
     *
     * 实现说明：
     * - useHttp 的 post 系列默认 payloadMode='query'，不便透传 FormData，故此处直接用 ofetch；
     * - 后端 POST /files 返回统一 ApiResponse 包装体 {status,message,data:{files:[...]}}，
     *   useHttp 内部会剥掉外层 data，但 ofetch 不会，故此处必须手动取 data.data.files；
     * - 401 处理与 useHttp 对齐：token 失效时清登录态并跳 /login，避免用户困在失效会话。
     */
    async function uploadImages(files: File[]): Promise<FileMetadataResponse[]> {
        const form = new FormData();
        for (const f of files) {
            form.append("files", f);
        }
        const envelope = await ofetch<ApiResult<FileUploadResponse>>("/files", {
            baseURL: apiBase,
            method: "POST",
            body: form,
            headers: {
                Accept: "application/json",
                ...authHeader(),
            },
        }).catch((error: any) => {
            const status = error?.response?.status ?? error?.statusCode ?? 0;
            if (status === UNAUTHORIZED_STATUS) {
                handleSessionExpired();
            }
            throw error;
        });
        return envelope?.data?.files ?? [];
    }

    return {
        list,
        get: getOne,
        create,
        update,
        remove,
        listTags,
        uploadImages,
    };
};

/* ===================== 品类 ↔ 类型映射辅助（仅供重载签名使用） ===================== */

/** 各品类对应的列表项联合（窄化为单一品类 Response）。 */
export type ProductListByType<T extends ProductType> =
    T extends "DRESS" ? DressResponse[]
    : T extends "BIKINI" ? BikiniSuitResponse[]
    : T extends "ONE_PIECE" ? OnePieceSuitResponse[]
    : T extends "COVER_UP" ? CoverUpResponse[]
    : never;

/** 各品类对应的响应项（解包后的实体）。 */
export type ResponseByType<T extends ProductType> =
    T extends "DRESS" ? DressResponse
    : T extends "BIKINI" ? BikiniSuitResponse
    : T extends "ONE_PIECE" ? OnePieceSuitResponse
    : T extends "COVER_UP" ? CoverUpResponse
    : never;

/** 各品类对应的 Upsert 请求体。 */
export type UpsertRequestByType<T extends ProductType> =
    T extends "DRESS" ? DressUpsertRequest
    : T extends "BIKINI" ? BikiniSuitUpsertRequest
    : T extends "ONE_PIECE" ? OnePieceSuitUpsertRequest
    : T extends "COVER_UP" ? CoverUpUpsertRequest
    : never;

/** 列表项联合（内部弱类型用）。 */
type ProductListItemUnion =
    | DressResponse
    | BikiniSuitResponse
    | OnePieceSuitResponse
    | CoverUpResponse;
