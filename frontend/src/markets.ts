// Sprint 10.13 (#3): EU/EEA market configuration (mirrors backend ai.budgetspace.product.Markets).
// Each market carries its own `currency` + `locale`; `formatCurrency` renders prices via
// Intl.NumberFormat, so non-EUR markets (Sprint 10.46: NO/SE/DK) display in their national currency.
// Adding a market still requires its own verified, currency-correct catalog (prices in that currency).
// `available` = a real per-market catalog exists today; others are foundation/"coming soon".
export type Lang = 'hr' | 'en' | 'de' | 'it' | 'sl' | 'fi' | 'fr' | 'nl' | 'sk' | 'es' | 'pt' | 'no' | 'sv' | 'da';

export interface MarketConfig {
  code: string;
  label: string;
  currency: string;
  locale: string;
  lang: Lang;
  available: boolean;
  flag: string;
}

// Beauty Kit Phase A: the launch market is Croatia ONLY. Every other market was removed from the
// picker because no beauty/nail catalog exists for it — offering a country we cannot build a kit for
// is exactly the false-completeness failure the product exists to avoid.
//
// What was deliberately NOT removed: the `Lang` union, the endonym table and the lazy-loaded
// `src/messages/*.json` overlays. Those are working multi-language infrastructure; an HR-only launch
// is a market decision, not a reason to delete translation capability. Re-adding a market means
// restoring its row here AND shipping a verified, currency-correct beauty catalog for it.
export const MARKETS: MarketConfig[] = [
  { code: 'HR', label: 'Hrvatska', currency: 'EUR', locale: 'hr-HR', lang: 'hr', available: true, flag: '🇭🇷' }
];

// Sprint 10.30: major cities per market for the optional city picker (datalist suggestions; the user can
// always type a different city). Kept in sync with the prompt city-detection patterns below.
export const CITIES_BY_MARKET: Record<string, string[]> = {
  HR: ['Zagreb', 'Split', 'Rijeka', 'Osijek', 'Zadar', 'Pula', 'Slavonski Brod', 'Karlovac', 'Varaždin', 'Šibenik', 'Dubrovnik', 'Sisak'],
};

export function citiesForMarket(code?: string): string[] {
  return CITIES_BY_MARKET[(code ?? 'HR').toUpperCase()] ?? [];
}

export function marketConfig(code?: string): MarketConfig {
  return MARKETS.find((market) => market.code === (code ?? 'HR')) ?? MARKETS[0];
}

export function isSupportedMarket(code?: string): boolean {
  return !!code && MARKETS.some((market) => market.code === code.toUpperCase());
}

// Each language's own name (endonym), for UI that offers to switch language TO/FROM it — e.g. the
// "Keep Deutsch" button on the browser-language suggestion. A visitor recognises their language by its
// native name, not an ISO code.
const LANG_ENDONYMS: Record<Lang, string> = {
  hr: 'Hrvatski', en: 'English', de: 'Deutsch', it: 'Italiano', sl: 'Slovenščina',
  fi: 'Suomi', fr: 'Français', nl: 'Nederlands', sk: 'Slovenčina', es: 'Español',
  pt: 'Português', no: 'Norsk', sv: 'Svenska', da: 'Dansk',
};

export function languageEndonym(lang: Lang): string {
  return LANG_ENDONYMS[lang] ?? lang.toUpperCase();
}

// Sprint 10.13 (#3): smart default — derive the market from the browser locale region (e.g.
// "de-DE" -> DE, "sl-SI" -> SI) so a visitor starts on a sensible country without doing anything.
// Returns undefined if the region isn't a market we support (caller falls back to HR).
export function regionToMarket(region?: string): string | undefined {
  if (!region) return undefined;
  const code = region.toUpperCase();
  return isSupportedMarket(code) ? code : undefined;
}

export function marketFromBrowser(): string | undefined {
  if (typeof navigator === 'undefined') return undefined;
  const langs = navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language];
  for (const lang of langs) {
    if (!lang) continue;
    const parts = lang.split('-');
    const region = parts.length > 1 ? parts[parts.length - 1] : undefined;
    const market = regionToMarket(region);
    if (market) return market;
  }
  return undefined;
}

// Sprint 10.13 (#3): detect the country from the user's prompt (city/country names) so people who
// just type "stan u Ljubljani" still get the right market. Diacritics are stripped before matching,
// so patterns are written in their plain-ASCII form. Only matches markets we actually support.
// HR-only launch: only Croatia is detectable. `isSupportedMarket` would reject every other entry
// anyway, so keeping them would be dead code that reads as if multi-market detection still works.
const MARKET_DETECTION: Array<{ market: string; pattern: RegExp }> = [
  { market: 'HR', pattern: /\b(hrvatsk\w*|croatia|zagreb\w*|split\w*|rijek\w*|osijek|zadar|pula|dubrovnik|varazdin|karlovac|sisak)\b/ }
];

function stripDiacritics(value: string): string {
  return value.toLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '');
}

export function detectMarketFromText(text?: string): string | undefined {
  if (!text) return undefined;
  const normalized = stripDiacritics(text);
  for (const entry of MARKET_DETECTION) {
    if (isSupportedMarket(entry.market) && entry.pattern.test(normalized)) {
      return entry.market;
    }
  }
  return undefined;
}
