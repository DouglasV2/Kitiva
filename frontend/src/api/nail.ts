// Vertical slice - nail endpoints. Mirrors the conventions in api/client.ts (same base URL, credentials
// included, JSON content type) without exporting a second generic request helper.
import type { Assumption, NailLookBrief } from '../types/beauty';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export interface NailParseResponse {
  brief: NailLookBrief;
  designDiagramSvg: string;
  needsExecutionModeAnswer: boolean;
  healthConcernDetected: boolean;
  forbiddenSystemRequested: boolean;
  forbiddenSystemNote: string;
  gelRequested: boolean;
}

export interface SalonSpecLine { labelHr: string; valueHr: string }

export interface SalonBrief {
  specification: SalonSpecLine[];
  placement: string[];
  showToTechnician: string;
  techniqueNotes: string[];
  simplerAlternative: string;
  variabilityDisclaimer: string;
}

export interface KitItem {
  slot: string;
  slotLabelHr: string;
  essential: boolean;
  externalId: string | null;
  name: string;
  shadeName: string | null;
  retailer: string | null;
  priceCents: number;
  productUrl: string | null;
  imageUrl: string | null;
  swatchImageUrl: string | null;
  whyHr: string;
  noteHr: string | null;
  ownedAlready: boolean;
}

export interface ValidatedKit {
  status: 'COMPLETE' | 'COMPLETE_WITH_ASSUMPTIONS' | 'INCOMPLETE_REQUIRED_ITEM_UNAVAILABLE' | 'OVER_BUDGET' | 'SAFETY_BLOCKED';
  statusLabelHr: string;
  statusExplanationHr: string;
  items: KitItem[];
  ownedItems: KitItem[];
  missingRequiredSlots: string[];
  essentialTotalCents: number;
  optionalTotalCents: number;
  totalCents: number;
  budgetCents: number | null;
  remainingCents: number | null;
  retailerCount: number;
  assumptions: Assumption[];
  safetyNotesHr: string[];
  catalogProvenanceHr: string;
}

export interface NailGenerateResponse {
  executionMode: string;
  brief: NailLookBrief;
  designDiagramSvg: string;
  salonBrief: SalonBrief | null;
  kit: ValidatedKit | null;
  blockedReasonHr: string | null;
}

async function post<T>(path: string, body: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
  } catch {
    throw new Error('Trenutno se ne mozemo spojiti. Pokusaj ponovno za koju minutu.');
  }
  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || `Zahtjev nije uspio (${response.status}).`);
  }
  return (await response.json()) as T;
}

export function parseNailPrompt(prompt: string, budgetCents: number): Promise<NailParseResponse> {
  return post<NailParseResponse>('/api/nail/parse', { prompt, budgetCents });
}

export function generateNailLook(brief: NailLookBrief, executionMode: 'SALON' | 'AT_HOME'): Promise<NailGenerateResponse> {
  return post<NailGenerateResponse>('/api/nail/generate', { brief, executionMode });
}
