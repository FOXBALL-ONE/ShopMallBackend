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
  BikiniSize,
  Coverage,
  CoverUpSize,
  CoverUpStyle,
  DressLength,
  DressNeckline,
  DressSilhouette,
  DressSize,
  DressSleeveType,
  OnePieceNeckline,
  OnePieceSize,
  ProductStatus,
  ProductType,
  SheerLevel,
  SupportLevel,
  TorsoFit,
} from "~/types/product";
import { getCategoryConfig, useProductApi } from "~/composables/useProductApi";

/**
 * 商品新增/编辑抽屉表单。
 *
 * 设计要点：
 * - 单一 reactive model 容纳所有品类的可编辑字段；按 category 用 v-if 决定专属字段区块的显隐。
 *   这样 rule 路径稳定（始终基于同一 model），避免切换品类时表单项 path 错位。
 * - 提交时仅拾取当前品类所需字段（公共 + 专属），构造 { [singularKey]: editableFields, tagIds }。
 * - 图片上传走 NUpload custom-request → useProductApi().uploadImages，把 signedDownloadUrl 回填 images。
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
  product: any | null;
  /** 父级已加载的标签列表。 */
  tags: { id: number; name: string }[];
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
const uploading = ref(false);
/** 编辑时商品 id（新增时为 null）。 */
const editingId = ref<number | null>(null);

const message = useMessage();

/** 当前品类配置，用于提交时取 singularKey 与路径。 */
const categoryConfig = computed(() => getCategoryConfig(props.category));

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
          value !== null && value > 0 ? true : new Error("价格必须大于 0"),
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
          value !== null && value >= 0 ? true : new Error("库存不能为负"),
        trigger: ["blur", "change"],
      },
    ],
    salesVolume: [
      {
        type: "number",
        message: "请输入销量",
        trigger: ["blur", "change"],
      },
    ],
    status: [{ required: true, message: "请选择状态", trigger: ["change"] }],
    highlight: [maxLengthArray(10, "卖点")],
    images: [maxLengthArray(12, "图片")],
    designAndExtras: [maxLengthArray(12, "设计细节")],
    careInstructions: [maxLengthArray(12, "洗护说明")],
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
  }

  return r;
});

/** 标签下拉选项，由父级传入的 Tag[] 映射。 */
const tagOptions = computed(() =>
  props.tags.map((t) => ({ label: t.name, value: t.id })),
);

/* ===================== 编辑回填 ===================== */

/** 抽屉开启时按 product 重置 model。 */
watch(
  () => props.open,
  (open) => {
    if (!open) return;
    Object.assign(model, createDefaultModel());
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
      if (props.category === "DRESS") {
        model.dressSize = p.size ?? null;
        model.dressLength = p.length ?? null;
        model.dressSilhouette = p.silhouette ?? null;
        model.dressNeckline = p.neckline ?? null;
        model.dressSleeveType = p.sleeveType ?? null;
        model.fabric = p.fabric ?? "";
      } else if (props.category === "BIKINI") {
        model.topSize = p.topSize ?? null;
        model.bottomSize = p.bottomSize ?? null;
      } else if (props.category === "ONE_PIECE") {
        model.onePieceSize = p.size ?? null;
        model.supportLevel = p.supportLevel ?? null;
        model.coverage = p.coverage ?? null;
        model.torsoFit = p.torsoFit ?? null;
        model.onePieceNeckline = p.neckline ?? null;
        model.backStyle = p.backStyle ?? null;
        model.tummyControl = !!p.tummyControl;
        model.removablePadding = !!p.removablePadding;
      } else if (props.category === "COVER_UP") {
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

const { uploadImages } = useProductApi();

/** NUpload custom-request：单/多文件上传后取 signedDownloadUrl 回填 images。 */
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
  if (model.images.length >= 12) {
    message.warning("图片最多 12 张，请先移除已有图片");
    onError();
    return;
  }
  uploading.value = true;
  try {
    const result = await uploadImages([rawFile]);
    const url = result[0]?.signedDownloadUrl;
    if (!url) {
      throw new Error("上传响应缺少 signedDownloadUrl");
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
  } catch (e: any) {
    message.error(`图片上传失败：${e?.message ?? e}`);
    onError();
  } finally {
    uploading.value = false;
  }
}

/** 移除已上传图片（按索引）。 */
function removeImage(index: number) {
  model.images.splice(index, 1);
}

/* ===================== 提交 ===================== */

/** 组装 UpsertRequest：仅拾取当前品类所需字段。 */
function buildPayload(): Record<string, unknown> {
  const base: Record<string, unknown> = {
    name: model.name,
    color: model.color,
    price: model.price,
    warehouseVolume: model.warehouseVolume,
    salesVolume: model.salesVolume,
    status: model.status,
    highlight: model.highlight,
    images: model.images,
    fitSense: model.fitSense || undefined,
    description: model.description || undefined,
    designAndExtras: model.designAndExtras,
    careInstructions: model.careInstructions,
  };

  const singularKey = categoryConfig.value.singularKey;
  const editable: Record<string, unknown> = { ...base };

  if (props.category === "DRESS") {
    editable.size = model.dressSize;
    editable.length = model.dressLength || undefined;
    editable.silhouette = model.dressSilhouette || undefined;
    editable.neckline = model.dressNeckline || undefined;
    editable.sleeveType = model.dressSleeveType || undefined;
    editable.fabric = model.fabric || undefined;
  } else if (props.category === "BIKINI") {
    editable.topSize = model.topSize || undefined;
    editable.bottomSize = model.bottomSize || undefined;
  } else if (props.category === "ONE_PIECE") {
    editable.size = model.onePieceSize;
    editable.supportLevel = model.supportLevel || undefined;
    editable.coverage = model.coverage || undefined;
    editable.torsoFit = model.torsoFit || undefined;
    editable.neckline = model.onePieceNeckline || undefined;
    editable.backStyle = model.backStyle || undefined;
    editable.tummyControl = model.tummyControl;
    editable.removablePadding = model.removablePadding;
  } else if (props.category === "COVER_UP") {
    editable.style = model.coverUpStyle || undefined;
    editable.sheerLevel = model.sheerLevel || undefined;
    editable.fabric = model.fabric || undefined;
    editable.size = model.coverUpSize;
  }

  return {
    [singularKey]: editable,
    tagIds: model.tagIds,
  };
}

async function handleSubmit() {
  try {
    await formRef.value?.validate();
  } catch {
    message.warning("请完成表单校验");
    return;
  }

  loading.value = true;
  try {
    const { create, update } = useProductApi();
    const payload = buildPayload();
    if (editingId.value !== null) {
      await update(props.category, editingId.value, payload as never);
    } else {
      await create(props.category, payload as never);
    }
    message.success(editingId.value !== null ? "已保存" : "已创建");
    emit("submitted");
    closeDrawer();
  } catch (e: any) {
    const msg = e?.statusMessage ?? e?.message ?? "提交失败";
    message.error(msg);
  } finally {
    loading.value = false;
  }
}

/** 关闭抽屉并重置。 */
function closeDrawer() {
  emit("update:open", false);
}

/** 抽屉宽度（720）。 */
const drawerWidth = 720;

/** 图片网格样式占位（内联避免 scoped 全局污染）。 */
const imageThumbStyle = "width: 96px; height: 96px; position: relative; border: 1px solid #e0e0e6; border-radius: 8px; overflow: hidden;";
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
      <NForm
        ref="formRef"
        :model="model"
        :rules="rules"
        label-placement="top"
        label-width="auto"
        require-mark-placement="right-hanging"
      >
        <!-- 公共字段区 -->
        <NDivider title-placement="left">基础信息</NDivider>
        <NGrid :cols="2" :x-gap="16" responsive="screen">
          <NFormItem label="商品名称" path="name" :span="2">
            <NInput v-model:value="model.name" placeholder="不超过 200 字符" clearable maxlength="200" show-count />
          </NFormItem>
          <NFormItem label="颜色" path="color">
            <NInput v-model:value="model.color" placeholder="如：黑色 / 海军蓝" clearable maxlength="50" />
          </NFormItem>
          <NFormItem label="状态" path="status">
            <NSelect v-model:value="model.status" :options="STATUS_OPTIONS" placeholder="选择状态" />
          </NFormItem>
          <NFormItem label="价格" path="price">
            <NInputNumber
              v-model:value="model.price"
              :min="0.01"
              :step="0.01"
              :precision="2"
              placeholder="大于 0"
              style="width: 100%"
            />
          </NFormItem>
          <NFormItem label="库存" path="warehouseVolume">
            <NInputNumber
              v-model:value="model.warehouseVolume"
              :min="0"
              :step="1"
              placeholder="≥0"
              style="width: 100%"
            />
          </NFormItem>
          <NFormItem v-if="editingId !== null" label="累计销量" path="salesVolume">
            <NInputNumber
              v-model:value="model.salesVolume"
              :min="0"
              :step="1"
              placeholder="≥0"
              style="width: 100%"
            />
          </NFormItem>
        </NGrid>

        <!-- 描述与卖点 -->
        <NGrid :cols="1" :x-gap="16" responsive="screen">
          <NFormItem label="版型感受" path="fitSense">
            <NInput v-model:value="model.fitSense" placeholder="可选，不超过 255 字符" clearable maxlength="255" />
          </NFormItem>
          <NFormItem label="描述" path="description">
            <NInput
              v-model:value="model.description"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 8 }"
              placeholder="可选，不超过 4000 字符"
              maxlength="4000"
              show-count
            />
          </NFormItem>
          <NFormItem label="卖点" path="highlight">
            <NDynamicTags v-model:value="model.highlight" :max="10" type="success" />
          </NFormItem>
          <NFormItem label="设计细节" path="designAndExtras">
            <NDynamicTags v-model:value="model.designAndExtras" :max="12" type="info" />
          </NFormItem>
          <NFormItem label="洗护说明" path="careInstructions">
            <NDynamicTags v-model:value="model.careInstructions" :max="12" type="warning" />
          </NFormItem>
        </NGrid>

        <!-- 图片上传区 -->
        <NDivider title-placement="left">图片</NDivider>
        <NFormItem label="商品图片" path="images">
          <NSpace vertical :size="12" style="width: 100%">
            <NUpload
              :custom-request="handleUploadRequest"
              multiple
              :show-file-list="false"
              :default-upload="true"
              accept="image/*"
            >
              <NButton :loading="uploading" :disabled="model.images.length >= 12">
                上传图片
              </NButton>
            </NUpload>
            <NText depth="3" style="font-size: 12px">
              已上传 {{ model.images.length }} / 12 张；上传后自动回填签名下载地址。
            </NText>
            <NSpace v-if="model.images.length" wrap :size="8">
              <div
                v-for="(url, idx) in model.images"
                :key="url + idx"
                :style="imageThumbStyle"
              >
                <NImage
                  :src="url"
                  object-fit="cover"
                  width="96"
                  height="96"
                  preview-disabled
                  style="display: block"
                />
                <NButton
                  size="tiny"
                  type="error"
                  ghost
                  style="position: absolute; top: 2px; right: 2px"
                  @click="removeImage(idx)"
                >
                  ×
                </NButton>
              </div>
            </NSpace>
          </NSpace>
        </NFormItem>

        <!-- 标签区 -->
        <NDivider title-placement="left">标签</NDivider>
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
        <NDivider title-placement="left">品类专属</NDivider>

        <!-- Dress -->
        <template v-if="category === 'DRESS'">
          <NGrid :cols="2" :x-gap="16" responsive="screen">
            <NFormItem label="尺码" path="dressSize">
              <NSelect v-model:value="model.dressSize" :options="DRESS_SIZE_OPTIONS" placeholder="必选" />
            </NFormItem>
            <NFormItem label="裙长" path="dressLength">
              <NSelect v-model:value="model.dressLength" :options="DRESS_LENGTH_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="轮廓" path="dressSilhouette">
              <NSelect v-model:value="model.dressSilhouette" :options="DRESS_SILHOUETTE_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="领口" path="dressNeckline">
              <NSelect v-model:value="model.dressNeckline" :options="DRESS_NECKLINE_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="袖型" path="dressSleeveType">
              <NSelect v-model:value="model.dressSleeveType" :options="DRESS_SLEEVE_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="面料" path="fabric">
              <NInput v-model:value="model.fabric" placeholder="可选，≤100 字符" clearable maxlength="100" />
            </NFormItem>
          </NGrid>
        </template>

        <!-- Bikini -->
        <template v-else-if="category === 'BIKINI'">
          <NGrid :cols="2" :x-gap="16" responsive="screen">
            <NFormItem label="上装尺码" path="topSize">
              <NSelect v-model:value="model.topSize" :options="BIKINI_SIZE_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="下装尺码" path="bottomSize">
              <NSelect v-model:value="model.bottomSize" :options="BIKINI_SIZE_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
          </NGrid>
        </template>

        <!-- OnePiece -->
        <template v-else-if="category === 'ONE_PIECE'">
          <NGrid :cols="2" :x-gap="16" responsive="screen">
            <NFormItem label="尺码" path="onePieceSize">
              <NSelect v-model:value="model.onePieceSize" :options="ONE_PIECE_SIZE_OPTIONS" placeholder="必选" />
            </NFormItem>
            <NFormItem label="支撑等级" path="supportLevel">
              <NSelect v-model:value="model.supportLevel" :options="SUPPORT_LEVEL_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="覆盖度" path="coverage">
              <NSelect v-model:value="model.coverage" :options="COVERAGE_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="躯干版型" path="torsoFit">
              <NSelect v-model:value="model.torsoFit" :options="TORSO_FIT_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="领口" path="onePieceNeckline">
              <NSelect v-model:value="model.onePieceNeckline" :options="ONE_PIECE_NECKLINE_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="背型" path="backStyle">
              <NSelect v-model:value="model.backStyle" :options="BACK_STYLE_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="收腹控制" path="tummyControl">
              <NSwitch v-model:value="model.tummyControl" />
            </NFormItem>
            <NFormItem label="可拆卸胸垫" path="removablePadding">
              <NSwitch v-model:value="model.removablePadding" />
            </NFormItem>
          </NGrid>
        </template>

        <!-- CoverUp -->
        <template v-else-if="category === 'COVER_UP'">
          <NGrid :cols="2" :x-gap="16" responsive="screen">
            <NFormItem label="风格" path="coverUpStyle">
              <NSelect v-model:value="model.coverUpStyle" :options="COVER_UP_STYLE_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="透视度" path="sheerLevel">
              <NSelect v-model:value="model.sheerLevel" :options="SHEER_LEVEL_OPTIONS" clearable placeholder="可选" />
            </NFormItem>
            <NFormItem label="面料" path="fabric">
              <NInput v-model:value="model.fabric" placeholder="可选，≤100 字符" clearable maxlength="100" />
            </NFormItem>
            <NFormItem label="尺码" path="coverUpSize">
              <NSelect v-model:value="model.coverUpSize" :options="COVER_UP_SIZE_OPTIONS" placeholder="必选" />
            </NFormItem>
          </NGrid>
        </template>
      </NForm>

      <template #footer>
        <NSpace>
          <NButton @click="closeDrawer">取消</NButton>
          <NButton type="primary" :loading="loading" @click="handleSubmit">
            {{ editingId !== null ? "保存" : "创建" }}
          </NButton>
        </NSpace>
      </template>
    </NDrawerContent>
  </NDrawer>
</template>
