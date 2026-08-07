// Phase B — the beauty/nail contract, mirroring backend hr.kitiva.beauty.dto.
//
// Kept as a SEPARATE file from types/index.ts rather than extending it. The furniture unions there
// (Retailer / RoomType / StyleType / ProductCategory) feed three total-mapped Record<> tables — category
// icons, fallback images and category labels — so widening them would force every beauty slot into
// furniture lookup tables that have no meaningful entry for "mascara". Phase E replaces those surfaces;
// until then the two vocabularies stay apart.
//
// Money is INTEGER CENTS everywhere, matching BeautyBriefDto. A makeup kit is eight items between €4 and
// €25, where repeated rounding can move a total by enough to flip an over-budget verdict.

/** The five honest outcomes of building a kit. Computed deterministically on the backend, never by an LLM. */
export type KitStatus =
  | 'COMPLETE'
  | 'COMPLETE_WITH_ASSUMPTIONS'
  | 'INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE'
  | 'OVER_BUDGET'
  | 'SAFETY_BLOCKED';

/** A kit may only be presented as a buyable shopping list in these two states. */
export const PURCHASABLE_STATUSES: readonly KitStatus[] = ['COMPLETE', 'COMPLETE_WITH_ASSUMPTIONS'];

export function isPurchasable(status: KitStatus): boolean {
  return PURCHASABLE_STATUSES.includes(status);
}

/** One inferred decision, so the user can see and correct it. Not a confidence score — see Assumption.java. */
export interface Assumption {
  field: string;
  /** Machine value. NEVER rendered — that is how "glossy" and "mirrored" ended up on screen. Use labelHr. */
  assumed: string;
  /** Natural Croatian phrase for what was assumed, e.g. "sjajni završni sloj". The only one to display. */
  labelHr: string;
  reasonHr: string;
}

/**
 * Something the user already owns — or explicitly does not.
 * `satisfiesRequirement: false` is "nemam lampu": a stated absence, which makes the slot REQUIRED.
 * It must never be treated as the same thing as the user simply not mentioning the slot.
 */
export interface OwnedItem {
  slot: string;
  productId?: string | null;
  rawText: string;
  satisfiesRequirement: boolean;
}

// ---------------------------------------------------------------------------------------------------
// Nail design
// ---------------------------------------------------------------------------------------------------

export type NailShape = 'ALMOND' | 'OVAL' | 'SQUARE' | 'SQUOVAL' | 'ROUND' | 'COFFIN' | 'STILETTO';
export type NailLength = 'SHORT' | 'MEDIUM' | 'LONG' | 'EXTRA_LONG';
export type NailFinish = 'GLOSSY' | 'MATTE' | 'SATIN' | 'SHIMMER';
export type NailEffect = 'NONE' | 'CAT_EYE' | 'CHROME' | 'FRENCH' | 'GLITTER_ACCENT';
export type NailFinger = 'THUMB' | 'INDEX' | 'MIDDLE' | 'RING' | 'PINKY';

/** No 'UNSPECIFIED' member on purpose: asymmetry must be a stated choice, never inferred from silence. */
export type NailSymmetry = 'MIRRORED' | 'ASYMMETRIC_STATED';

/** Lengths that generally need an extension — the signal that routes an at-home look toward press-ons. */
export const LENGTHS_NEEDING_EXTENSION: readonly NailLength[] = ['LONG', 'EXTRA_LONG'];

/**
 * The single source for all three nail outputs: the Design Diagram, the salon brief, and the material kit.
 * Nothing may be rendered, said or bought that is not represented here.
 */
export interface NailDesignSpec {
  shape: NailShape;
  length: NailLength;
  /** Canonical colour key, e.g. 'burgundy'. */
  baseColorKey: string;
  /** Hex for the Design Diagram; null when the canonical key has no known swatch. */
  baseColorHex?: string | null;
  /** Her own words, echoed back so she can tell we understood — e.g. 'boja višnje'. */
  baseColorRawText: string;
  finish: NailFinish;
  effects: NailEffect[];
  /** Named fingers, because 'prstenjak' is what she says and what the technician hears. */
  accentFingers: NailFinger[];
  /**
   * The accent's own colour, separate from the base. "dva diskretna zlatna detalja" is two colours in one
   * sentence, so the diagram has to draw both and the kit has to buy both.
   */
  accentColorKey: string;
  accentColorHex?: string | null;
  accentDescription: string;
  symmetry: NailSymmetry;
  styleWords: string[];
  assumptions: Assumption[];
}

export function accentCount(design: NailDesignSpec): number {
  return design.accentFingers.length;
}

export function requiresExtension(design: NailDesignSpec): boolean {
  return LENGTHS_NEEDING_EXTENSION.includes(design.length);
}

// ---------------------------------------------------------------------------------------------------
// Briefs
// ---------------------------------------------------------------------------------------------------

/**
 * How the user intends to get the nails done. 'UNSPECIFIED' exists so the UI asks instead of guessing —
 * the two branches differ fundamentally, and one of them sells a consumer a chemical system.
 */
export type NailExecutionMode = 'UNSPECIFIED' | 'SALON' | 'AT_HOME';

export type NailExperienceLevel = 'FIRST_TIME' | 'SOME' | 'ADVANCED';

/** Skill and equipment only. No health data is collected anywhere in the MVP — see audit §6.6. */
export interface NailHomeProfile {
  experienceLevel: NailExperienceLevel;
  naturalNailLength: NailLength;
  equipment: OwnedItem[];
}

export interface NailLookBrief {
  prompt: string;
  market: string;
  currency: string;
  budgetCents: number;
  budgetStrict: boolean;
  includeShipping: boolean;
  executionMode: NailExecutionMode;
  design: NailDesignSpec;
  homeProfile?: NailHomeProfile | null;
  singleRetailerOnly: boolean;
  assumptions: Assumption[];
}

export function needsExecutionModeAnswer(brief: NailLookBrief): boolean {
  return brief.executionMode === 'UNSPECIFIED';
}

/**
 * The makeup request.
 *
 * `skinType` is the ordinary cosmetic attribute every beauty retailer filters on. `gentleEyeAreaPreferred`
 * and `fragranceFreePreferred` are PRODUCT PREFERENCES — they steer selection toward products whose
 * manufacturer makes that claim. Neither is a declared health condition, and no health condition is stored.
 */
export interface BeautyBrief {
  prompt: string;
  market: string;
  currency: string;
  budgetCents: number;
  budgetStrict: boolean;
  includeShipping: boolean;
  look: string;
  occasion: string;
  styleWords: string[];
  coverage: string;
  finish: string;
  skinType: string;
  skinToneDepth: string;
  undertone: string;
  /** An existing shade she already wears — the only signal strong enough to justify a close match. */
  referenceShade: string;
  gentleEyeAreaPreferred: boolean;
  fragranceFreePreferred: boolean;
  ownedItems: OwnedItem[];
  requiredSlots: string[];
  excludedSlots: string[];
  brandPreferences: string[];
  singleRetailerOnly: boolean;
  skillLevel: string;
  assumptions: Assumption[];
}

export const CENTS_PER_EURO = 100;

/** The single euro-to-cent boundary on the client, mirroring BeautyBriefDto.eurosToCents. */
export function eurosToCents(euros: number): number {
  return Math.max(0, Math.round(euros)) * CENTS_PER_EURO;
}

export function centsToEuros(cents: number): number {
  return cents / CENTS_PER_EURO;
}

/** Slots she owns, so the engine can skip them. Excludes explicit 'nemam' entries. */
export function ownedSlots(items: OwnedItem[]): string[] {
  return items.filter((item) => item.satisfiesRequirement).map((item) => item.slot);
}

/** Slots she explicitly said she lacks — required even when otherwise optional. */
export function explicitlyMissingSlots(items: OwnedItem[]): string[] {
  return items.filter((item) => !item.satisfiesRequirement).map((item) => item.slot);
}
