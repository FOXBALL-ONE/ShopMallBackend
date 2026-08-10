<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useMessage } from "naive-ui";
import type {
  FormInst,
  FormRules,
  SelectOption,
  UploadCustomRequestOptions,
} from "naive-ui";
import type {
  BackStyle,
  BikiniSuitEditable,
  BikiniSize,
  Coverage,
  CoverUpEditable,
  CoverUpSize,
  CoverUpStyle,
  DressLength,
  DressEditable,
  DressNeckline,
  DressSilhouette,
  DressSize,
  DressSleeveType,
  OnePieceNeckline,
  OnePieceSuitEditable,
  OnePieceSize,
  ProductListItem,
  ProductStatus,
  ProductType,
  ProductUpsertRequest,
  SheerLevel,
  SupportLevel,
  Tag,
  TorsoFit,
} from "~/types/product";
import { CATEGORIES, useProductApi } from "~/composables/useProductApi";

/**
 * 商品新增/编辑抽屉表单。
 *
 * 设计要点：
 * - 单一 reactive model 容纳所有品类的可编辑字段；按 category 用 v-if 决定专属字段区块的显隐。
 *   这样 rule 路径稳定（始终基于同一 model），避免切换品类时表单项 path 错位。
 * - 提交时仅拾取当前品类所需字段（公共 + 专属），构造 { [singularKey]: editableFields, tagIds }。
 * - 图片上传走 NUpload custom-request → useProductApi().uploadImages，把稳定图片 URL 回填 images。
 * - 编辑回填通过 watch props.open + props.product：抽屉常驻挂载，每次开启都按当前 product 重置。
 * - 枚举选项集中定义为 ENUM_OPTIONS 常量，供 NSelect 复用；中文 label，value 保持后端原始字面量。
 */

/* ===================== 枚举选项常量（4 品类所有枚举） ===================== */

// 选项统一使用 naive-ui 的 SelectOption 类型（见顶部 import），
// 可直接绑定 NSelect :options；各枚举 value 字面量与后端 DTO 对齐，字面量约束由 model 字段保证。

/** 商品状态（表单仅允许选上架/下架；已删除由删除按钮触发，不在编辑项内暴露）。 */
const STATUS_OPTIONS: SelectOption[] = [
  { label: "上架", value: "ACTIVE" },
  { label: "下架", value: "INACTIVE" },
];

/** Dress 尺寸。 */
const DRESS_SIZE_OPTIONS: SelectOption[] = [
  "XS", "S", "M", "L", "XL", "XXL",
].map((v) => ({ label: v, value: v }));

/** Dress 裙长。 */
const DRESS_LENGTH_OPTIONS: SelectOption[] = [
  { label: "及踝长裙", value: "MAXI" },
  { label: "中长裙", value: "MIDI" },
  { label: "短裙", value: "MINI" },
];

/** Dress 轮廓。 */
const DRESS_SILHOUETTE_OPTIONS: SelectOption[] = [
  { label: "吊带裙", value: "SLIP" },
  { label: "A 字裙", value: "A_LINE" },
  { label: "衬衫裙", value: "SHIRT" },
  { label: "裹身裙", value: "WRAP" },
  { label: "直筒裙", value: "SHIFT" },
  { label: "修身裙", value: "BODYCON" },
  { label: "抽褶裙", value: "SMOKED" },
];

/** Dress 领口。 */
const DRESS_NECKLINE_OPTIONS: SelectOption[] = [
  { label: "圆领", value: "SCOOP" },
  { label: "V 领", value: "V_NECK" },
  { label: "心形领", value: "SWEETHEART" },
  { label: "挂脖", value: "HALTER" },
  { label: "露肩", value: "OFF_SHOULDER" },
  { label: "圆口领", value: "ROUND" },
  { label: "方领", value: "SQUARE" },
];

/** Dress 袖型。 */
const DRESS_SLEEVE_OPTIONS: SelectOption[] = [
  { label: "无袖", value: "SLEEVELESS" },
  { label: "短袖", value: "SHORT" },
  { label: "坎袖", value: "CAP" },
  { label: "七分袖", value: "THREE_QUARTER" },
  { label: "长袖", value: "LONG" },
  { label: "泡泡袖", value: "PUFF" },
  { label: "喇叭袖", value: "BELL" },
];

/** BikiniSuit 上/下装尺码（同集合）。 */
const BIKINI_SIZE_OPTIONS: SelectOption[] = [
  "S", "M", "L", "XL", "XXL", "XXXL", "XXXXL",
].map((v) => ({ label: v, value: v }));

/** OnePieceSuit 尺码（含更小/更大档）。 */
const ONE_PIECE_SIZE_OPTIONS: SelectOption[] = [
  "XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL", "XXXXL", "XXXXXL",
].map((v) => ({ label: v, value: v }));

/** OnePieceSuit 支撑等级。 */
const SUPPORT_LEVEL_OPTIONS: SelectOption[] = [
  { label: "轻度支撑", value: "LIGHT" },
  { label: "中度支撑", value: "MEDIUM" },
  { label: "高度支撑", value: "HIGH" },
];

/** OnePieceSuit 覆盖度。 */
const COVERAGE_OPTIONS: SelectOption[] = [
  { label: "微露", value: "CHEEKY" },
  { label: "适中", value: "MODERATE" },
  { label: "全覆盖", value: "FULL" },
];

/** OnePieceSuit 躯干版型。 */
const TORSO_FIT_OPTIONS: SelectOption[] = [
  { label: "短躯干", value: "SHORT" },
  { label: "常规", value: "REGULAR" },
  { label: "长躯干", value: "LONG" },
];

/** OnePieceSuit 领口。 */
const ONE_PIECE_NECKLINE_OPTIONS: SelectOption[] = [
  { label: "圆领", value: "SCOOP" },
  { label: "V 领", value: "V_NECK" },
  { label: "挂脖", value: "HALTER" },
  { label: "抹胸", value: "BANDEAU" },
  { label: "单肩", value: "ONE_SHOULDER" },
  { label: "高领", value: "HIGH_NECK" },
];

/** OnePieceSuit 背型。 */
const BACK_STYLE_OPTIONS: SelectOption[] = [
  { label: "露背", value: "OPEN_BACK" },
  { label: "交叉背", value: "CROSS_BACK" },
  { label: "挖背", value: "SCOOP_BACK" },
  { label: "拉链背", value: "ZIP_BACK" },
  { label: "全背", value: "FULL_BACK" },
];

/** CoverUp 风格。 */
const COVER_UP_STYLE_OPTIONS: SelectOption[] = [
  { label: "和服", value: "KIMONO" },
  { label: "长衫", value: "TUNIC" },
  { label: "罩袍", value: "ROBE" },
  { label: "斗篷", value: "PONCHO" },
  { label: "裹身", value: "WRAP" },
  { label: "长罩衫", value: "DUSTER" },
];

/** CoverUp 透视度。 */
const SHEER_LEVEL_OPTIONS: SelectOption[] = [
  { label: "透视", value: "SHEER" },
  { label: "半透视", value: "SEMI_SHEER" },
  { label: "不透明", value: "OPAQUE" },
];

/** CoverUp 尺码（默认 ONE_SIZE）。 */
const COVER_UP_SIZE_OPTIONS: SelectOption[] = [
  "ONE_SIZE", "XS", "S", "M", "L", "XL", "XXL",
].map((v) => ({ label: v === "ONE_SIZE" ? "均码" : v, value: v }));

/* ===================== Props / Emits ===================== */

const props = defineProps<{
  /** 抽屉显隐，v-model:open。 */
  open: boolean;
  /** 当前品类。 */
  category: ProductType;
  /** 编辑时传入现有 Response；新增时为 null。 */
  product: ProductListItem | null;
  /** 父级已加载的标签列表。 */
  tags: Tag[];
}>();

const emit = defineEmits<{
  (e: "update:open", value: boolean): void;
  (e: "submitted"): void;
}>();

/* ===================== 表单 model ===================== */

/**
 * 全字段表单 model（跨品类聚合）。
 * 不在品类间拆分多个 model，是因为 NForm 的 path 需稳定指向同一 reactive 对象的键，
 * 切换品类时显隐控件即可，无需重建 model。
 */
interface FormModel {
  // 公共字段
  name: string;
  color: string;
  price: number | null;
  warehouseVolume: number | null;
  salesVolume: number;
  status: ProductStatus;
  highlight: string[];
  images: string[];
  fitSense: string;
  description: string;
  designAndExtras: string[];
  careInstructions: string[];
  // 标签
  tagIds: number[];
  // Dress 专属
  dressSize: DressSize | null;
  dressLength: DressLength | null;
  dressSilhouette: DressSilhouette | null;
  dressNeckline: DressNeckline | null;
  dressSleeveType: DressSleeveType | null;
  fabric: string;
  // Bikini 专属
  topSize: BikiniSize | null;
  bottomSize: BikiniSize | null;
  // OnePiece 专属
  onePieceSize: OnePieceSize | null;
  supportLevel: SupportLevel | null;
  coverage: Coverage | null;
  torsoFit: TorsoFit | null;
  onePieceNeckline: OnePieceNeckline | null;
  backStyle: BackStyle | null;
  tummyControl: boolean;
  removablePadding: boolean;
  // CoverUp 专属
  coverUpStyle: CoverUpStyle | null;
  sheerLevel: SheerLevel | null;
  coverUpSize: CoverUpSize | null;
}

/** 生成新增默认 model。CoverUp 的 size 默认 ONE_SIZE，其余必填项置 null 以触发必填校验。 */
function createDefaultModel(): FormModel {
  return {
    name: "",
    color: "",
    price: null,
    warehouseVolume: 0,
    salesVolume: 0,
    status: "ACTIVE",
    highlight: [],
    images: [],
    fitSense: "",
    description: "",
    designAndExtras: [],
    careInstructions: [],
    tagIds: [],
    dressSize: null,
    dressLength: null,
    dressSilhouette: null,
    dressNeckline: null,
    dressSleeveType: null,
    fabric: "",
    topSize: null,
    bottomSize: null,
    onePieceSize: null,
    supportLevel: null,
    coverage: null,
    torsoFit: null,
    onePieceNeckline: null,
    backStyle: null,
    tummyControl: false,
    removablePadding: false,
    coverUpStyle: null,
    sheerLevel: null,
    coverUpSize: "ONE_SIZE",
  };
}

const model = reactive<FormModel>(createDefaultModel());

const formRef = ref<FormInst | null>(null);
const loading = ref(false);
const pendingUploads = ref(0);
const uploading = computed(() => pendingUploads.value > 0);
const manualImageUrl = ref("");
/** 编辑时商品 id（新增时为 null）。 */
const editingId = ref<number | null>(null);
const isEditing = computed(() => editingId.value !== null);

const categoryLabel = computed(
  () => CATEGORIES.find((item) => item.type === props.category)?.label ?? "商品",
);

/** 上新时展示轻量进度，帮助运营在提交前快速发现尚未补全的核心信息。 */
const setupProgress = computed(() => {
  const categoryFieldReady = {
    DRESS: !!model.dressSize,
    BIKINI: !!model.topSize || !!model.bottomSize,
    ONE_PIECE: !!model.onePieceSize,
    COVER_UP: !!model.coverUpSize,
  }[props.category];
  const completeCount = [
    !!model.name.trim(),
    !!model.color.trim(),
    typeof model.price === "number" && model.price > 0,
    model.images.length > 0,
    categoryFieldReady,
  ].filter(Boolean).length;
  return { completeCount, total: 5 };
});

const message = useMessage();
const productApi = useProductApi();

function errorMessage(error: unknown): string {
  if (error && typeof error === "object") {
    const value = error as { statusMessage?: string; message?: string };
    return value.statusMessage || value.message || "未知错误";
  }
  return String(error || "未知错误");
}

/* ===================== 校验规则 ===================== */

/** 数组长度上限校验器工厂。 */
function maxLengthArray(max: number, fieldLabel: string) {
  return {
    trigger: ["change", "blur"],
    validator: (_rule: unknown, value: unknown) => {
      const len = Array.isArray(value) ? value.length : 0;
      return len <= max ? true : new Error(`${fieldLabel}最多 ${max} 项`);
    },
  };
}

function textArrayRule(max: number, itemMax: number, fieldLabel: string) {
  return {
    trigger: ["change", "blur"],
    validator: (_rule: unknown, value: unknown) => {
      if (!Array.isArray(value)) return true;
      if (value.length > max) return new Error(`${fieldLabel}最多 ${max} 项`);
      if (value.some((item) => typeof item !== "string" || !item.trim())) {
        return new Error(`${fieldLabel}不能包含空项`);
      }
      return value.some((item) => item.length > itemMax)
        ? new Error(`${fieldLabel}每项不超过 ${itemMax} 字符`)
        : true;
    },
  };
}

const rules = computed<FormRules>(() => {
  const r: FormRules = {
    name: [
      { required: true, message: "请输入商品名称", trigger: ["blur", "input"] },
      { max: 200, message: "名称不超过 200 字符", trigger: ["blur", "input"] },
    ],
    color: [
      { required: true, message: "请输入颜色", trigger: ["blur", "input"] },
      { max: 50, message: "颜色不超过 50 字符", trigger: ["blur", "input"] },
    ],
    price: [
      {
        required: true,
        type: "number",
        message: "请输入价格",
        trigger: ["blur", "change"],
      },
      {
        validator: (_rule: unknown, value: number | null) =>
          value !== null
          && value > 0
          && value <= 99_999_999.99
          && Math.abs(value * 100 - Math.round(value * 100)) < 1e-8
            ? true
            : new Error("价格需大于 0、最多两位小数且不超过 99999999.99"),
        trigger: ["blur", "change"],
      },
    ],
    warehouseVolume: [
      {
        required: true,
        type: "number",
        message: "请输入库存",
        trigger: ["blur", "change"],
      },
      {
        validator: (_rule: unknown, value: number | null) =>
          value !== null && Number.isInteger(value) && value >= 0
            ? true
            : new Error("库存必须是非负整数"),
        trigger: ["blur", "change"],
      },
    ],
    status: [{ required: true, message: "请选择状态", trigger: ["change"] }],
    highlight: [textArrayRule(10, 255, "卖点")],
    images: [textArrayRule(12, 512, "图片地址")],
    designAndExtras: [textArrayRule(12, 255, "设计细节")],
    careInstructions: [textArrayRule(12, 255, "洗护说明")],
    tagIds: [maxLengthArray(20, "标签")],
    fitSense: [{ max: 255, message: "不超过 255 字符", trigger: ["blur", "input"] }],
    description: [{ max: 4000, message: "不超过 4000 字符", trigger: ["blur", "input"] }],
    fabric: [{ max: 100, message: "不超过 100 字符", trigger: ["blur", "input"] }],
  };

  // 各品类必填 size（Bikini 上下装均可空，不设必填）
  if (props.category === "DRESS") {
    r.dressSize = [{ required: true, message: "请选择尺码", trigger: ["change"] }];
  } else if (props.category === "ONE_PIECE") {
    r.onePieceSize = [{ required: true, message: "请选择尺码", trigger: ["change"] }];
  } else if (props.category === "COVER_UP") {
    r.coverUpSize = [{ required: true, message: "请选择尺码", trigger: ["change"] }];
  } else if (props.category === "BIKINI") {
    r.topSize = [{
      validator: () => model.topSize || model.bottomSize ? true : new Error("上装和下装尺码至少选择一项"),
      trigger: ["change"],
    }];
  }

  return r;
});

/** 标签下拉选项，由父级传入的 Tag[] 映射。 */
const tagOptions = computed(() =>
  props.tags.map((t) => ({
    label: t.active ? t.name : `${t.name}（已停用）`,
    value: t.id,
    disabled: !t.active && !model.tagIds.includes(t.id),
  })),
);

/* ===================== 编辑回填 ===================== */

/** 抽屉开启时按 product 重置 model。 */
watch(
  () => props.open,
  (open) => {
    if (!open) return;
    Object.assign(model, createDefaultModel());
    manualImageUrl.value = "";
    if (props.product) {
      const p = props.product;
      editingId.value = p.id ?? null;
      // 公共字段
      model.name = p.name ?? "";
      model.color = p.color ?? "";
      const numericPrice = Number(p.price);
      model.price = Number.isFinite(numericPrice) ? numericPrice : null;
      model.warehouseVolume = p.warehouseVolume ?? 0;
      model.salesVolume = p.salesVolume ?? 0;
      model.status = p.status ?? "ACTIVE";
      model.highlight = Array.isArray(p.highlight) ? [...p.highlight] : [];
      model.images = Array.isArray(p.images) ? [...p.images] : [];
      model.fitSense = p.fitSense ?? "";
      model.description = p.description ?? "";
      model.designAndExtras = Array.isArray(p.designAndExtras) ? [...p.designAndExtras] : [];
      model.careInstructions = Array.isArray(p.careInstructions) ? [...p.careInstructions] : [];
      // tags → tagIds
      model.tagIds = Array.isArray(p.tags) ? p.tags.map((t: { id: number }) => t.id) : [];
      // 专属字段（按品类回填，避免空赋值造成噪音）
      if (p.productType === "DRESS") {
        model.dressSize = p.size ?? null;
        model.dressLength = p.length ?? null;
        model.dressSilhouette = p.silhouette ?? null;
        model.dressNeckline = p.neckline ?? null;
        model.dressSleeveType = p.sleeveType ?? null;
        model.fabric = p.fabric ?? "";
      } else if (p.productType === "BIKINI") {
        model.topSize = p.topSize ?? null;
        model.bottomSize = p.bottomSize ?? null;
      } else if (p.productType === "ONE_PIECE") {
        model.onePieceSize = p.size ?? null;
        model.supportLevel = p.supportLevel ?? null;
        model.coverage = p.coverage ?? null;
        model.torsoFit = p.torsoFit ?? null;
        model.onePieceNeckline = p.neckline ?? null;
        model.backStyle = p.backStyle ?? null;
        model.tummyControl = !!p.tummyControl;
        model.removablePadding = !!p.removablePadding;
      } else if (p.productType === "COVER_UP") {
        model.coverUpStyle = p.style ?? null;
        model.sheerLevel = p.sheerLevel ?? null;
        model.coverUpSize = p.size ?? "ONE_SIZE";
        model.fabric = p.fabric ?? "";
      }
    } else {
      editingId.value = null;
    }
  },
  { immediate: true },
);

/* ===================== 图片上传 ===================== */

/** NUpload custom-request：单/多文件上传后取稳定地址回填 images。 */
async function handleUploadRequest({
  file,
  onFinish,
  onError,
}: UploadCustomRequestOptions) {
  const rawFile = file.file as File | null;
  if (!rawFile) {
    onError();
    return;
  }
  // 已达上限直接拒绝（multiple 并发场景下，push 前再次校验作为最终闸门）
  if (model.images.length + pendingUploads.value >= 12) {
    message.warning("图片最多 12 张，请先移除已有图片");
    onError();
    return;
  }
  pendingUploads.value += 1;
  try {
    const result = await productApi.uploadImages([rawFile]);
    const url = result[0]?.stableUrl;
    if (!url) {
      throw new Error("上传响应缺少 stableUrl");
    }
    // multiple 并发上传时多个 custom-request 可能同时通过上方校验；
    // push 前最终确认未越界，越界则丢弃本次结果
    if (model.images.length >= 12) {
      message.warning("图片已达 12 张上限，部分新图未保留");
      onFinish();
      return;
    }
    model.images.push(url);
    onFinish();
  } catch (error) {
    message.error(`图片上传失败：${errorMessage(error)}`);
    onError();
  } finally {
    pendingUploads.value -= 1;
  }
}

/** 移除已上传图片（按索引）。 */
function removeImage(index: number) {
  model.images.splice(index, 1);
}

/** 将第一张图片作为商城商品列表卡片的封面。 */
function moveImage(index: number, direction: -1 | 1) {
  const targetIndex = index + direction;
  if (targetIndex < 0 || targetIndex >= model.images.length) return;
  const image = model.images[index];
  if (image === undefined) return;
  model.images.splice(index, 1);
  model.images.splice(targetIndex, 0, image);
}

/** 支持补录已经托管在 CDN / 图片服务中的 HTTPS 地址。 */
function addImageUrl() {
  if (model.images.length >= 12) {
    message.warning("图片最多 12 张，请先移除已有图片");
    return;
  }
  const url = manualImageUrl.value.trim();
  if (!url) return;
  try {
    const parsedUrl = new URL(url);
    if (!/^https?:$/.test(parsedUrl.protocol)) throw new Error("unsupported protocol");
  } catch {
    message.warning("请输入以 http:// 或 https:// 开头的有效图片地址");
    return;
  }
  if (model.images.includes(url)) {
    message.warning("该图片已经添加");
    return;
  }
  model.images.push(url);
  manualImageUrl.value = "";
}

/* ===================== 提交 ===================== */

/** 组装 UpsertRequest：仅拾取当前品类所需字段。 */
function buildPayload(): ProductUpsertRequest {
  const base = {
    name: model.name,
    color: model.color,
    price: model.price!,
    warehouseVolume: model.warehouseVolume ?? 0,
    salesVolume: model.salesVolume,
    status: model.status,
    highlight: model.highlight,
    images: model.images,
    fitSense: model.fitSense || undefined,
    description: model.description || undefined,
    designAndExtras: model.designAndExtras,
    careInstructions: model.careInstructions,
  };

  if (props.category === "DRESS") {
    const dress: DressEditable = {
      ...base,
      size: model.dressSize!,
      length: model.dressLength || undefined,
      silhouette: model.dressSilhouette || undefined,
      neckline: model.dressNeckline || undefined,
      sleeveType: model.dressSleeveType || undefined,
      fabric: model.fabric || undefined,
    };
    return { dress, tagIds: model.tagIds };
  }
  if (props.category === "BIKINI") {
    const bikiniSuit: BikiniSuitEditable = {
      ...base,
      topSize: model.topSize || undefined,
      bottomSize: model.bottomSize || undefined,
    };
    return { bikiniSuit, tagIds: model.tagIds };
  }
  if (props.category === "ONE_PIECE") {
    const onePieceSuit: OnePieceSuitEditable = {
      ...base,
      size: model.onePieceSize!,
      supportLevel: model.supportLevel || undefined,
      coverage: model.coverage || undefined,
      torsoFit: model.torsoFit || undefined,
      neckline: model.onePieceNeckline || undefined,
      backStyle: model.backStyle || undefined,
      tummyControl: model.tummyControl,
      removablePadding: model.removablePadding,
    };
    return { onePieceSuit, tagIds: model.tagIds };
  }
  const coverUp: CoverUpEditable = {
    ...base,
    style: model.coverUpStyle || undefined,
    sheerLevel: model.sheerLevel || undefined,
    fabric: model.fabric || undefined,
    size: model.coverUpSize!,
  };
  return { coverUp, tagIds: model.tagIds };
}

async function handleSubmit() {
  if (uploading.value) {
    message.warning("图片仍在上传中");
    return;
  }
  try {
    await formRef.value?.validate();
  } catch {
    message.warning("请完成表单校验");
    return;
  }

  loading.value = true;
  try {
    const payload = buildPayload();
    if (editingId.value !== null) {
      await productApi.update(props.category, editingId.value, payload);
    } else {
      await productApi.create(props.category, payload);
    }
    message.success(editingId.value !== null ? "已保存" : "已创建");
    emit("submitted");
    closeDrawer();
  } catch (error) {
    message.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

/** 关闭抽屉并重置。 */
function closeDrawer() {
  emit("update:open", false);
}

/** 抽屉在窄屏下不超出视口。 */
const drawerWidth = "min(720px, 100vw)";

</script>

<template>
  <NDrawer
    :show="open"
    :width="drawerWidth"
    placement="right"
    @update:show="(v: boolean) => emit('update:open', v)"
  >
    <NDrawerContent
      :title="editingId !== null ? '编辑商品' : '新增商品'"
      :native-scrollbar="false"
      closable
    >
      <div class="product-form-intro">
        <div>
          <NSpace align="center" :size="8">
            <NTag type="info" size="small" :bordered="false">{{ categoryLabel }}</NTag>
            <NText strong>{{ isEditing ? "更新商品资料" : "创建一件新商品" }}</NText>
          </NSpace>
          <NText depth="3" class="product-form-intro-copy">
            {{ isEditing ? "保存后会同步更新商城中的商品内容。" : "先完成核心信息、商品图片和品类属性，再发布到商城。" }}
          </NText>
        </div>
        <div v-if="!isEditing" class="setup-progress">
          <NText depth="3" style="font-size: 12px">核心信息 {{ setupProgress.completeCount }} / {{ setupProgress.total }}</NText>
          <NProgress
            type="line"
            :percentage="(setupProgress.completeCount / setupProgress.total) * 100"
            :show-indicator="false"
            :height="6"
            status="success"
          />
        </div>
      </div>

      <NForm
        ref="formRef"
        :model="model"
        :rules="rules"
        label-placement="top"
        label-width="auto"
        require-mark-placement="right-hanging"
      >
        <!-- 公共字段区 -->
        <NDivider title-placement="left">1. 上新核心</NDivider>
        <NGrid cols="1 s:2" :x-gap="16" responsive="screen">
          <NFormItemGi label="商品名称" path="name" :span="2">
            <NInput v-model:value="model.name" placeholder="用清晰的名称帮助顾客快速识别商品" clearable maxlength="200" show-count />
          </NFormItemGi>
          <NFormItemGi label="颜色" path="color">
            <NInput v-model:value="model.color" placeholder="如：黑色 / 海军蓝" clearable maxlength="50" />
          </NFormItemGi>
          <NFormItemGi v-if="!isEditing" label="初始状态" path="status">
            <NSelect v-model:value="model.status" :options="STATUS_OPTIONS" placeholder="选择状态" />
          </NFormItemGi>
          <NFormItemGi label="价格" path="price">
            <NInputNumber
              v-model:value="model.price"
              :min="0.01"
              :step="0.01"
              :precision="2"
              placeholder="输入售价，例如 299.00"
              style="width: 100%"
            />
          </NFormItemGi>
          <NFormItemGi v-if="!isEditing" label="初始库存" path="warehouseVolume">
            <NInputNumber
              v-model:value="model.warehouseVolume"
              :min="0"
              :step="1"
              placeholder="≥0"
              style="width: 100%"
            />
          </NFormItemGi>
        </NGrid>
        <NDescriptions v-if="isEditing" :column="2" bordered size="small" label-placement="top">
          <NDescriptionsItem label="当前库存">{{ model.warehouseVolume }}</NDescriptionsItem>
          <NDescriptionsItem label="累计销量">{{ model.salesVolume }}</NDescriptionsItem>
        </NDescriptions>

        <!-- 图片上传区 -->
        <NDivider title-placement="left">2. 商品图片</NDivider>
        <NFormItem label="商品图片" path="images">
          <NSpace vertical :size="12" style="width: 100%">
            <NUpload
              :custom-request="handleUploadRequest"
              multiple
              :show-file-list="false"
              :default-upload="true"
              accept="image/*"
            >
              <NUploadDragger :disabled="uploading || model.images.length >= 12" class="image-upload-dragger">
                <div class="image-upload-content">
                  <NText strong>{{ uploading ? "正在上传图片…" : "拖拽图片到这里，或点击选择文件" }}</NText>
                  <NText depth="3" style="font-size: 12px">支持一次选择多张图片，最多 12 张。</NText>
                </div>
              </NUploadDragger>
            </NUpload>
            <NInputGroup>
              <NInput
                v-model:value="manualImageUrl"
                :disabled="model.images.length >= 12"
                clearable
                placeholder="或粘贴已有图片的 HTTPS 地址"
                @keyup.enter="addImageUrl"
              />
              <NButton :disabled="!manualImageUrl.trim() || model.images.length >= 12" @click="addImageUrl">
                添加链接
              </NButton>
            </NInputGroup>
            <div class="image-section-note">
              <NText depth="3" style="font-size: 12px">
                已添加 {{ model.images.length }} / 12 张。第一张会作为商品封面显示在商城列表中。
              </NText>
            </div>
            <div v-if="model.images.length" class="image-grid">
              <div
                v-for="(url, idx) in model.images"
                :key="url + idx"
                class="image-card"
                :class="{ 'image-card-cover': idx === 0 }"
              >
                <NImage
                  :src="url"
                  object-fit="cover"
                  width="112"
                  height="128"
                  preview-disabled
                  style="display: block"
                />
                <NTag v-if="idx === 0" type="success" size="small" :bordered="false" class="image-cover-tag">封面</NTag>
                <div class="image-card-actions">
                  <NButton size="tiny" :disabled="idx === 0" @click="moveImage(idx, -1)">前移</NButton>
                  <NButton size="tiny" :disabled="idx === model.images.length - 1" @click="moveImage(idx, 1)">后移</NButton>
                  <NButton size="tiny" type="error" @click="removeImage(idx)">移除</NButton>
                </div>
              </div>
            </div>
          </NSpace>
        </NFormItem>

        <!-- 描述与卖点 -->
        <NDivider title-placement="left">3. 商品内容</NDivider>
        <NGrid :cols="1" :x-gap="16" responsive="screen">
          <NFormItemGi label="版型感受" path="fitSense">
            <NInput v-model:value="model.fitSense" placeholder="例如：修身、高腰、弹力舒适" clearable maxlength="255" />
          </NFormItemGi>
          <NFormItemGi label="商品描述" path="description">
            <NInput
              v-model:value="model.description"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 8 }"
              placeholder="介绍面料、穿着场景和设计特点，最多 4000 字符"
              maxlength="4000"
              show-count
            />
          </NFormItemGi>
          <NFormItemGi label="核心卖点" path="highlight">
            <NDynamicTags v-model:value="model.highlight" :max="10" type="success" />
          </NFormItemGi>
          <NFormItemGi label="设计细节" path="designAndExtras">
            <NDynamicTags v-model:value="model.designAndExtras" :max="12" type="info" />
          </NFormItemGi>
          <NFormItemGi label="洗护说明" path="careInstructions">
            <NDynamicTags v-model:value="model.careInstructions" :max="12" type="warning" />
          </NFormItemGi>
        </NGrid>

        <!-- 标签区 -->
        <NDivider title-placement="left">4. 标签与商品属性</NDivider>
        <NFormItem label="商品标签" path="tagIds">
          <NSelect
            v-model:value="model.tagIds"
            :options="tagOptions"
            :max="20"
            multiple
            filterable
            clearable
            placeholder="选择标签（可多选，最多 20 个）"
          />
        </NFormItem>

        <!-- 品类专属字段区 -->
        <NText depth="3" class="category-fields-hint">请补充 {{ categoryLabel }} 的规格信息；标记为“必选”的字段需要完成后才能创建。</NText>

        <!-- Dress -->
        <template v-if="category === 'DRESS'">
          <NGrid cols="1 s:2" :x-gap="16" responsive="screen">
            <NFormItemGi label="尺码" path="dressSize">
              <NSelect v-model:value="model.dressSize" :options="DRESS_SIZE_OPTIONS" placeholder="必选" />
            </NFormItemGi>
            <NFormItemGi label="裙长" path="dressLength">
              <NSelect v-model:value="model.dressLength" :options="DRESS_LENGTH_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="轮廓" path="dressSilhouette">
              <NSelect v-model:value="model.dressSilhouette" :options="DRESS_SILHOUETTE_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="领口" path="dressNeckline">
              <NSelect v-model:value="model.dressNeckline" :options="DRESS_NECKLINE_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="袖型" path="dressSleeveType">
              <NSelect v-model:value="model.dressSleeveType" :options="DRESS_SLEEVE_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="面料" path="fabric">
              <NInput v-model:value="model.fabric" placeholder="可选，≤100 字符" clearable maxlength="100" />
            </NFormItemGi>
          </NGrid>
        </template>

        <!-- Bikini -->
        <template v-else-if="category === 'BIKINI'">
          <NGrid cols="1 s:2" :x-gap="16" responsive="screen">
            <NFormItemGi label="上装尺码" path="topSize">
              <NSelect v-model:value="model.topSize" :options="BIKINI_SIZE_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="下装尺码" path="bottomSize">
              <NSelect v-model:value="model.bottomSize" :options="BIKINI_SIZE_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
          </NGrid>
        </template>

        <!-- OnePiece -->
        <template v-else-if="category === 'ONE_PIECE'">
          <NGrid cols="1 s:2" :x-gap="16" responsive="screen">
            <NFormItemGi label="尺码" path="onePieceSize">
              <NSelect v-model:value="model.onePieceSize" :options="ONE_PIECE_SIZE_OPTIONS" placeholder="必选" />
            </NFormItemGi>
            <NFormItemGi label="支撑等级" path="supportLevel">
              <NSelect v-model:value="model.supportLevel" :options="SUPPORT_LEVEL_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="覆盖度" path="coverage">
              <NSelect v-model:value="model.coverage" :options="COVERAGE_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="躯干版型" path="torsoFit">
              <NSelect v-model:value="model.torsoFit" :options="TORSO_FIT_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="领口" path="onePieceNeckline">
              <NSelect v-model:value="model.onePieceNeckline" :options="ONE_PIECE_NECKLINE_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="背型" path="backStyle">
              <NSelect v-model:value="model.backStyle" :options="BACK_STYLE_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="收腹控制" path="tummyControl">
              <NSwitch v-model:value="model.tummyControl" />
            </NFormItemGi>
            <NFormItemGi label="可拆卸胸垫" path="removablePadding">
              <NSwitch v-model:value="model.removablePadding" />
            </NFormItemGi>
          </NGrid>
        </template>

        <!-- CoverUp -->
        <template v-else-if="category === 'COVER_UP'">
          <NGrid cols="1 s:2" :x-gap="16" responsive="screen">
            <NFormItemGi label="风格" path="coverUpStyle">
              <NSelect v-model:value="model.coverUpStyle" :options="COVER_UP_STYLE_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="透视度" path="sheerLevel">
              <NSelect v-model:value="model.sheerLevel" :options="SHEER_LEVEL_OPTIONS" clearable placeholder="可选" />
            </NFormItemGi>
            <NFormItemGi label="面料" path="fabric">
              <NInput v-model:value="model.fabric" placeholder="可选，≤100 字符" clearable maxlength="100" />
            </NFormItemGi>
            <NFormItemGi label="尺码" path="coverUpSize">
              <NSelect v-model:value="model.coverUpSize" :options="COVER_UP_SIZE_OPTIONS" placeholder="必选" />
            </NFormItemGi>
          </NGrid>
        </template>
      </NForm>

      <template #footer>
        <NSpace justify="end">
          <NButton @click="closeDrawer">取消</NButton>
          <NButton type="primary" :loading="loading" :disabled="uploading" @click="handleSubmit">
            {{ editingId !== null ? "保存更新" : "创建商品" }}
          </NButton>
        </NSpace>
      </template>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped>
.product-form-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 4px 0 18px;
}

.product-form-intro-copy {
  display: block;
  margin-top: 6px;
  font-size: 13px;
}

.setup-progress {
  flex: 0 0 148px;
}

.image-upload-dragger {
  min-height: 96px;
}

.image-upload-content {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  justify-content: center;
  min-height: 74px;
  text-align: center;
}

.image-section-note {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(112px, 1fr));
  gap: 12px;
}

.image-card {
  position: relative;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--n-border-color);
  border-radius: 8px;
  background: var(--n-color-modal);
}

.image-card-cover {
  border-color: var(--n-primary-color);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--n-primary-color) 20%, transparent);
}

.image-card :deep(img) {
  width: 100%;
}

.image-cover-tag {
  position: absolute;
  top: 6px;
  left: 6px;
}

.image-card-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  padding: 6px;
}

.image-card-actions :last-child {
  grid-column: 1 / -1;
}

.category-fields-hint {
  display: block;
  margin: -4px 0 12px;
  font-size: 12px;
}

@media (max-width: 520px) {
  .product-form-intro {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }

  .setup-progress {
    width: 100%;
  }
}
</style>
