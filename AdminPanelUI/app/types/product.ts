/**
 * 商品管理相关类型定义。
 * 对齐后端 top.foxball.shopmall.product.* 各品类 DTO，
 * 以及 top.foxball.shopmall.tag.Tag、top.foxball.shopmall.file.FileMetadataResponse。
 *
 * 约定：
 * - 后端 BigDecimal（precision=10, scale=2）在前端统一用 number 表示；
 * - 所有时间字段为 ISO-8601 字符串；
 * - 列表/单项响应均落在 useHttp 已剥掉外层的 ApiResult.data 之内。
 */

/** 商品状态：上架 / 下架 / 已删除（软删除，列表仍返回但前端需标注）。 */
export type ProductStatus = "ACTIVE" | "INACTIVE" | "DELETED";

/** 商品品类鉴别字段，用于在联合类型中区分具体品类。 */
export type ProductType = "BIKINI" | "ONE_PIECE" | "DRESS" | "COVER_UP";

/* ===================== 各品类枚举（string literal union） ===================== */

/** Dress 尺寸。 */
export type DressSize = "XS" | "S" | "M" | "L" | "XL" | "XXL";

/** Dress 裙长。 */
export type DressLength = "MAXI" | "MIDI" | "MINI";

/** Dress 轮廓。 */
export type DressSilhouette = "SLIP" | "A_LINE" | "SHIRT" | "WRAP" | "SHIFT" | "BODYCON" | "SMOKED";

/** 领口（Dress 与 OnePieceSuit 共用，但取值集合不同，故分别定义）。 */
export type DressNeckline =
    | "SCOOP"
    | "V_NECK"
    | "SWEETHEART"
    | "HALTER"
    | "OFF_SHOULDER"
    | "ROUND"
    | "SQUARE";

/** Dress 袖型。 */
export type DressSleeveType =
    | "SLEEVELESS"
    | "SHORT"
    | "CAP"
    | "THREE_QUARTER"
    | "LONG"
    | "PUFF"
    | "BELL";

/** BikiniSuit 上装尺码。 */
export type BikiniSize = "S" | "M" | "L" | "XL" | "XXL" | "XXXL" | "XXXXL";

/** OnePieceSuit 尺码（含更小 / 更大档）。 */
export type OnePieceSize =
    | "XXS"
    | "XS"
    | "S"
    | "M"
    | "L"
    | "XL"
    | "XXL"
    | "XXXL"
    | "XXXXL"
    | "XXXXXL";

/** OnePieceSuit 支撑等级。 */
export type SupportLevel = "LIGHT" | "MEDIUM" | "HIGH";

/** OnePieceSuit 覆盖度。 */
export type Coverage = "CHEEKY" | "MODERATE" | "FULL";

/** OnePieceSuit 躯干版型。 */
export type TorsoFit = "SHORT" | "REGULAR" | "LONG";

/** OnePieceSuit 领口。 */
export type OnePieceNeckline =
    | "SCOOP"
    | "V_NECK"
    | "HALTER"
    | "BANDEAU"
    | "ONE_SHOULDER"
    | "HIGH_NECK";

/** OnePieceSuit 背型。 */
export type BackStyle =
    | "OPEN_BACK"
    | "CROSS_BACK"
    | "SCOOP_BACK"
    | "ZIP_BACK"
    | "FULL_BACK";

/** CoverUp 风格。 */
export type CoverUpStyle =
    | "KIMONO"
    | "TUNIC"
    | "ROBE"
    | "PONCHO"
    | "WRAP"
    | "DUSTER";

/** CoverUp 透视度。 */
export type SheerLevel = "SHEER" | "SEMI_SHEER" | "OPAQUE";

/** CoverUp 尺码（默认 ONE_SIZE）。 */
export type CoverUpSize = "ONE_SIZE" | "XS" | "S" | "M" | "L" | "XL" | "XXL";

/* ===================== 标签 / 文件元数据 ===================== */

/** 标签，对齐后端 Tag。 */
export interface Tag {
    id: number;
    name: string;
    description?: string;
    color?: string;
    sortOrder: number;
    active: boolean;
    createdAt?: string;
    updatedAt?: string;
}

/** 文件元数据响应，对齐后端 FileMetadataResponse。 */
export interface FileMetadataResponse {
    id: string;
    fileName: string;
    contentType?: string;
    sizeBytes: number;
    sha256: string;
    createdAt?: string;
    signedDownloadUrl: string;
    downloadExpiresAt: string;
    scope: string;
    storage: string;
}

/* ===================== 公共可编辑字段 ===================== */

/**
 * 商品公共可编辑字段（所有品类共有，applyBaseChangesFrom 确认）。
 * id / score / createdAt / updatedAt / tags 不在此处——提交时用 tagIds。
 */
export interface ProductBase {
    /** 商品名（必填，max 200）。 */
    name: string;
    /** 颜色（必填，max 50）。 */
    color: string;
    /** 价格，BigDecimal scale=2，前端用 number（>0，整数部分 ≤8 位）。 */
    price: number;
    /** 库存（≥0）。 */
    warehouseVolume: number;
    /** 累计销量（≥0，新增时给 0；管理员表单可编辑但通常只读展示）。 */
    salesVolume: number;
    /** 状态，默认 ACTIVE。 */
    status: ProductStatus;
    /** 亮点（≤10 条）。 */
    highlight: string[];
    /** 图片 URL（≤12 张）。 */
    images: string[];
    /** 贴合感（max 255，可选）。 */
    fitSense?: string;
    /** 描述（max 4000，可选）。 */
    description?: string;
    /** 设计与附加（≤12 条）。 */
    designAndExtras: string[];
    /** 洗护说明（≤12 条）。 */
    careInstructions: string[];
}

/* ===================== 各品类可编辑体（公共 + 专属） ===================== */

/** Dress 可编辑字段（size 必填，其余专属字段可选）。 */
export interface DressEditable extends ProductBase {
    size: DressSize;
    length?: DressLength;
    silhouette?: DressSilhouette;
    neckline?: DressNeckline;
    sleeveType?: DressSleeveType;
    fabric?: string;
}

/** BikiniSuit 可编辑字段（上下装尺码均可空，但至少填一个更合理）。 */
export interface BikiniSuitEditable extends ProductBase {
    topSize?: BikiniSize;
    bottomSize?: BikiniSize;
}

/** OnePieceSuit 可编辑字段（size 必填，布尔字段默认 false）。 */
export interface OnePieceSuitEditable extends ProductBase {
    size: OnePieceSize;
    supportLevel?: SupportLevel;
    coverage?: Coverage;
    torsoFit?: TorsoFit;
    neckline?: OnePieceNeckline;
    backStyle?: BackStyle;
    tummyControl: boolean;
    removablePadding: boolean;
}

/** CoverUp 可编辑字段（size 默认 ONE_SIZE，非空）。 */
export interface CoverUpEditable extends ProductBase {
    style?: CoverUpStyle;
    sheerLevel?: SheerLevel;
    fabric?: string;
    size: CoverUpSize;
}

/* ===================== 各品类 Upsert 请求体 ===================== */

/** 新增/更新请求体结构：内嵌实体 + tagIds。 */
export interface DressUpsertRequest {
    dress: DressEditable;
    tagIds: number[];
}

export interface BikiniSuitUpsertRequest {
    bikiniSuit: BikiniSuitEditable;
    tagIds: number[];
}

export interface OnePieceSuitUpsertRequest {
    onePieceSuit: OnePieceSuitEditable;
    tagIds: number[];
}

export interface CoverUpUpsertRequest {
    coverUp: CoverUpEditable;
    tagIds: number[];
}

/** 通用 Upsert 请求体（联合），便于 API 层弱类型传递。 */
export type ProductUpsertRequest =
    | DressUpsertRequest
    | BikiniSuitUpsertRequest
    | OnePieceSuitUpsertRequest
    | CoverUpUpsertRequest;

/* ===================== 各品类 Response（含不可编辑字段） ===================== */

/** Dress 响应。 */
export interface DressResponse extends DressEditable {
    id: number;
    score?: number;
    tags: Tag[];
    createdAt?: string;
    updatedAt?: string;
    /** 品类鉴别字段。 */
    productType: "DRESS";
}

/** BikiniSuit 响应。 */
export interface BikiniSuitResponse extends BikiniSuitEditable {
    id: number;
    score?: number;
    tags: Tag[];
    createdAt?: string;
    updatedAt?: string;
    productType: "BIKINI";
}

/** OnePieceSuit 响应。 */
export interface OnePieceSuitResponse extends OnePieceSuitEditable {
    id: number;
    score?: number;
    tags: Tag[];
    createdAt?: string;
    updatedAt?: string;
    productType: "ONE_PIECE";
}

/** CoverUp 响应。 */
export interface CoverUpResponse extends CoverUpEditable {
    id: number;
    score?: number;
    tags: Tag[];
    createdAt?: string;
    updatedAt?: string;
    productType: "COVER_UP";
}

/* ===================== 列表 / 单项 / 删除响应 ===================== */

/** Dress 列表响应（data 内层）。 */
export interface DressListResponse {
    dresses: DressResponse[];
}

export interface DressItemResponse {
    dress: DressResponse;
}

/** BikiniSuit 列表 / 单项响应。 */
export interface BikiniSuitListResponse {
    bikiniSuits: BikiniSuitResponse[];
}

export interface BikiniSuitItemResponse {
    bikiniSuit: BikiniSuitResponse;
}

/** OnePieceSuit 列表 / 单项响应。 */
export interface OnePieceSuitListResponse {
    onePieceSuits: OnePieceSuitResponse[];
}

export interface OnePieceSuitItemResponse {
    onePieceSuit: OnePieceSuitResponse;
}

/** CoverUp 列表 / 单项响应。 */
export interface CoverUpListResponse {
    coverUps: CoverUpResponse[];
}

export interface CoverUpItemResponse {
    coverUp: CoverUpResponse;
}

/** 删除响应（软删除 status=DELETED）。 */
export interface ProductDeleteResponse {
    id: number;
    deleted: boolean;
}

/* ===================== 联合类型（便于列表统一处理） ===================== */

/** 四个品类响应的联合类型。 */
export type ProductListItem =
    | DressResponse
    | BikiniSuitResponse
    | OnePieceSuitResponse
    | CoverUpResponse;

/** 四个品类单项响应的联合类型。 */
export type ProductItemResponse =
    | DressItemResponse
    | BikiniSuitItemResponse
    | OnePieceSuitItemResponse
    | CoverUpItemResponse;
