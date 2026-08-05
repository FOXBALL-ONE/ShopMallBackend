import { ofetch } from "ofetch";
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

export interface CategoryConfig {
    type: ProductType;
    label: string;
    basePath: string;
    singularKey: string;
}

export const CATEGORIES: readonly CategoryConfig[] = [
    { type: "DRESS", label: "连衣裙", basePath: "/dresses", singularKey: "dress" },
    { type: "BIKINI", label: "比基尼", basePath: "/bikini-suits", singularKey: "bikiniSuit" },
    { type: "ONE_PIECE", label: "一件式", basePath: "/one-piece-suits", singularKey: "onePieceSuit" },
    { type: "COVER_UP", label: "罩衫", basePath: "/cover-ups", singularKey: "coverUp" },
] as const;

export function getCategoryConfig(type: ProductType): CategoryConfig {
    const config = CATEGORIES.find(category => category.type === type);
    if (!config) throw new Error(`未知的商品品类: ${type}`);
    return config;
}

interface RawTag {
    id: number;
    name: string;
    description?: string | null;
    color?: string | null;
    sort_order: number;
    active: boolean;
    created_at?: string | null;
    updated_at?: string | null;
}

interface RawProduct {
    id: number;
    name: string;
    color: string;
    price: number | string;
    warehouse_volume: number;
    sales_volume: number;
    status: "ACTIVE" | "INACTIVE" | "DELETED";
    highlight?: string[];
    images?: string[];
    fit_sense?: string | null;
    description?: string | null;
    design_and_extras?: string[];
    care_instructions?: string[];
    score?: number | null;
    tag_ids?: number[];
    created_at?: string | null;
    updated_at?: string | null;
    size?: string;
    length?: string | null;
    silhouette?: string | null;
    neckline?: string | null;
    sleeve_type?: string | null;
    fabric?: string | null;
    top_size?: string | null;
    bottom_size?: string | null;
    support_level?: string | null;
    coverage?: string | null;
    torso_fit?: string | null;
    back_style?: string | null;
    tummy_control?: boolean;
    removable_padding?: boolean;
    style?: string | null;
    sheer_level?: string | null;
}

interface RawProductImage {
    id: string;
    file_name: string;
    content_type?: string | null;
    size_bytes: number;
    sha256: string;
    created_at?: string | null;
    stable_url: string;
    storage: string;
}

const UNAUTHORIZED_STATUS = 401;

function normalizeProduct(raw: RawProduct, category: ProductType): ProductListItemUnion {
    const tags: Tag[] = (raw.tag_ids ?? []).map(id => ({
        id,
        name: String(id),
        sortOrder: 0,
        active: true,
    }));
    const base = {
        id: raw.id,
        name: raw.name,
        color: raw.color,
        price: Number(raw.price),
        warehouseVolume: raw.warehouse_volume,
        salesVolume: raw.sales_volume,
        status: raw.status,
        highlight: raw.highlight ?? [],
        images: raw.images ?? [],
        fitSense: raw.fit_sense ?? undefined,
        description: raw.description ?? undefined,
        designAndExtras: raw.design_and_extras ?? [],
        careInstructions: raw.care_instructions ?? [],
        score: raw.score ?? undefined,
        tags,
        createdAt: raw.created_at ?? undefined,
        updatedAt: raw.updated_at ?? undefined,
    };

    if (category === "DRESS") {
        return {
            ...base,
            productType: "DRESS",
            size: raw.size,
            length: raw.length ?? undefined,
            silhouette: raw.silhouette ?? undefined,
            neckline: raw.neckline ?? undefined,
            sleeveType: raw.sleeve_type ?? undefined,
            fabric: raw.fabric ?? undefined,
        } as DressResponse;
    }
    if (category === "BIKINI") {
        return {
            ...base,
            productType: "BIKINI",
            topSize: raw.top_size ?? undefined,
            bottomSize: raw.bottom_size ?? undefined,
        } as BikiniSuitResponse;
    }
    if (category === "ONE_PIECE") {
        return {
            ...base,
            productType: "ONE_PIECE",
            size: raw.size,
            supportLevel: raw.support_level ?? undefined,
            coverage: raw.coverage ?? undefined,
            torsoFit: raw.torso_fit ?? undefined,
            neckline: raw.neckline ?? undefined,
            backStyle: raw.back_style ?? undefined,
            tummyControl: raw.tummy_control ?? false,
            removablePadding: raw.removable_padding ?? false,
        } as OnePieceSuitResponse;
    }
    return {
        ...base,
        productType: "COVER_UP",
        style: raw.style ?? undefined,
        sheerLevel: raw.sheer_level ?? undefined,
        fabric: raw.fabric ?? undefined,
        size: raw.size,
    } as CoverUpResponse;
}

function toWirePayload(category: ProductType, payload: unknown): Record<string, unknown> {
    const config = getCategoryConfig(category);
    const request = payload as Record<string, unknown>;
    const item = (request[config.singularKey] ?? {}) as Record<string, unknown>;
    const wire: Record<string, unknown> = {
        name: item.name,
        color: item.color,
        price: item.price,
        warehouse_volume: item.warehouseVolume,
        sales_volume: item.salesVolume,
        status: item.status,
        highlight: item.highlight,
        images: item.images,
        fit_sense: item.fitSense,
        description: item.description,
        design_and_extras: item.designAndExtras,
        care_instructions: item.careInstructions,
        tag_ids: request.tagIds,
    };

    if (category === "DRESS") {
        Object.assign(wire, {
            size: item.size,
            length: item.length,
            silhouette: item.silhouette,
            neckline: item.neckline,
            sleeve_type: item.sleeveType,
            fabric: item.fabric,
        });
    } else if (category === "BIKINI") {
        Object.assign(wire, { top_size: item.topSize, bottom_size: item.bottomSize });
    } else if (category === "ONE_PIECE") {
        Object.assign(wire, {
            size: item.size,
            support_level: item.supportLevel,
            coverage: item.coverage,
            torso_fit: item.torsoFit,
            neckline: item.neckline,
            back_style: item.backStyle,
            tummy_control: item.tummyControl,
            removable_padding: item.removablePadding,
        });
    } else {
        Object.assign(wire, {
            style: item.style,
            sheer_level: item.sheerLevel,
            fabric: item.fabric,
            size: item.size,
        });
    }

    return Object.fromEntries(Object.entries(wire).filter(([, value]) => value !== undefined && value !== null));
}

export const useProductApi = () => {
    const runtimeConfig = useRuntimeConfig();
    const adminApiBase = (runtimeConfig.public.adminApiBase as string) || "http://127.0.0.1:8080/admin/api";
    const { get, post, put, delete: del } = useHttp(adminApiBase);
    const authToken = useCookie<string | null>("admin_auth_token");
    const authUser = useCookie<unknown | null>("admin_user_info");
    const route = useRoute();

    function authHeader(): Record<string, string> {
        const token = authToken.value?.trim();
        if (!token) return {};
        return { Authorization: /^bearer\s+/i.test(token) ? token : `Bearer ${token}` };
    }

    function handleSessionExpired() {
        authToken.value = null;
        authUser.value = null;
        if (import.meta.server) {
            throw createError({ statusCode: 401, statusMessage: "Unauthorized" });
        }
        if (route.path !== "/login") navigateTo("/login");
    }

    function list<T extends ProductType>(category: T): Promise<ProductListByType<T>>;
    function list(category: ProductType): Promise<ProductListItemUnion[]> {
        const config = getCategoryConfig(category);
        return get<{ list: RawProduct[] }>(config.basePath)
            .then(data => (data?.list ?? []).map(item => normalizeProduct(item, category)));
    }

    function getOne<T extends ProductType>(category: T, id: number): Promise<ResponseByType<T>>;
    function getOne(category: ProductType, id: number): Promise<ProductListItemUnion> {
        const config = getCategoryConfig(category);
        return get<RawProduct>(`${config.basePath}/${id}`).then(data => normalizeProduct(data, category));
    }

    async function create<T extends ProductType>(category: T, payload: UpsertRequestByType<T>): Promise<ResponseByType<T>> {
        const config = getCategoryConfig(category);
        const result = await post<{ id: number }>(config.basePath, toWirePayload(category, payload));
        return getOne(category, result.id);
    }

    async function update<T extends ProductType>(
        category: T,
        id: number,
        payload: UpsertRequestByType<T>,
    ): Promise<ResponseByType<T>> {
        const config = getCategoryConfig(category);
        await put<{ id: number }>(`${config.basePath}/${id}`, toWirePayload(category, payload));
        return getOne(category, id);
    }

    function remove(category: ProductType, id: number): Promise<ProductDeleteResponse> {
        return del<ProductDeleteResponse>(`${getCategoryConfig(category).basePath}/${id}`);
    }

    function listTags(): Promise<Tag[]> {
        return get<{ list: RawTag[] }>("/tags").then(data => (data?.list ?? []).map(tag => ({
            id: tag.id,
            name: tag.name,
            description: tag.description ?? undefined,
            color: tag.color ?? undefined,
            sortOrder: tag.sort_order,
            active: tag.active,
            createdAt: tag.created_at ?? undefined,
            updatedAt: tag.updated_at ?? undefined,
        })));
    }

    async function uploadImages(files: File[]): Promise<FileMetadataResponse[]> {
        const form = new FormData();
        files.forEach(file => form.append("files", file));
        const envelope = await ofetch<ApiResult<{ list: RawProductImage[] }>>("/product-images", {
            baseURL: adminApiBase,
            method: "POST",
            body: form,
            credentials: "include",
            headers: { Accept: "application/json", ...authHeader() },
        }).catch((error: any) => {
            const status = error?.response?.status ?? error?.statusCode ?? 0;
            if (status === UNAUTHORIZED_STATUS) handleSessionExpired();
            throw error;
        });
        return (envelope?.data?.list ?? []).map(file => ({
            id: file.id,
            fileName: file.file_name,
            contentType: file.content_type ?? undefined,
            sizeBytes: file.size_bytes,
            sha256: file.sha256,
            createdAt: file.created_at ?? undefined,
            stableUrl: file.stable_url,
            storage: file.storage,
        }));
    }

    return { list, get: getOne, create, update, remove, listTags, uploadImages };
};

export type ProductListByType<T extends ProductType> =
    T extends "DRESS" ? DressResponse[]
    : T extends "BIKINI" ? BikiniSuitResponse[]
    : T extends "ONE_PIECE" ? OnePieceSuitResponse[]
    : T extends "COVER_UP" ? CoverUpResponse[]
    : never;

export type ResponseByType<T extends ProductType> =
    T extends "DRESS" ? DressResponse
    : T extends "BIKINI" ? BikiniSuitResponse
    : T extends "ONE_PIECE" ? OnePieceSuitResponse
    : T extends "COVER_UP" ? CoverUpResponse
    : never;

export type UpsertRequestByType<T extends ProductType> =
    T extends "DRESS" ? DressUpsertRequest
    : T extends "BIKINI" ? BikiniSuitUpsertRequest
    : T extends "ONE_PIECE" ? OnePieceSuitUpsertRequest
    : T extends "COVER_UP" ? CoverUpUpsertRequest
    : never;

type ProductListItemUnion = DressResponse | BikiniSuitResponse | OnePieceSuitResponse | CoverUpResponse;
