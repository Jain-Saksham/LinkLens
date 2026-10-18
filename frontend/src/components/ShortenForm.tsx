import { type FormEvent, useId, useMemo, useState } from 'react';
import { ApiError, createShortUrl, type ShortUrl } from '../api/client';
import { EXPIRY_OPTIONS, type ExpiryPreset, resolveExpiry, toLocalInputValue, validateCustomExpiry } from '../lib/expiry';
import { normalizeUrl, validateAlias } from '../lib/validation';
import { AlertIcon, ArrowRightIcon, ChevronIcon } from './icons';

interface Props {
  onCreated: (link: ShortUrl) => void;
}

type FieldErrors = Partial<Record<'url' | 'customAlias' | 'expiresAt', string>>;

const SHORT_HOST = new URL(import.meta.env.VITE_SHORT_BASE_URL || 'http://localhost:8080').host;

export function ShortenForm({ onCreated }: Props) {
  const ids = { url: useId(), alias: useId(), expiry: useId() };

  const [url, setUrl] = useState('');
  const [alias, setAlias] = useState('');
  const [preset, setPreset] = useState<ExpiryPreset>('never');
  const [customExpiry, setCustomExpiry] = useState(() => toLocalInputValue(new Date(Date.now() + 86_400_000)));
  const [showOptions, setShowOptions] = useState(false);

  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);

  const minLocal = useMemo(() => toLocalInputValue(new Date()), []);

  function validate(): { errors: FieldErrors; normalizedUrl: string | null } {
    const next: FieldErrors = {};
    const normalizedUrl = normalizeUrl(url);
    if (!url.trim()) next.url = 'Paste a link to shorten';
    else if (!normalizedUrl) next.url = 'That doesn’t look like a valid web address';

    const aliasError = validateAlias(alias.trim());
    if (aliasError) next.customAlias = aliasError;

    if (preset === 'custom') {
      const expiryError = validateCustomExpiry(customExpiry);
      if (expiryError) next.expiresAt = expiryError;
    }
    return { errors: next, normalizedUrl };
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setFormError(null);

    const { errors: clientErrors, normalizedUrl } = validate();
    setErrors(clientErrors);
    if (Object.keys(clientErrors).length > 0 || !normalizedUrl) {
      if (clientErrors.customAlias || clientErrors.expiresAt) setShowOptions(true);
      return;
    }

    setSubmitting(true);
    try {
      const link = await createShortUrl({
        url: normalizedUrl,
        customAlias: alias.trim() || undefined,
        expiresAt: resolveExpiry(preset, customExpiry),
      });
      onCreated(link);
      setUrl('');
      setAlias('');
      setPreset('never');
    } catch (err) {
      if (err instanceof ApiError) {
        // Server-side rules (alias taken, self-link) land next to the field they concern.
        if (err.status === 409) {
          setErrors({ customAlias: 'This alias is already taken — try another' });
          setShowOptions(true);
        } else if (Object.keys(err.fieldErrors).length > 0) {
          setErrors(err.fieldErrors as FieldErrors);
        } else if (err.status === 400) {
          setErrors({ url: err.message });
        } else {
          setFormError(err.message);
        }
      } else {
        setFormError('Something went wrong. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      noValidate
      className="rounded-[28px] border border-line bg-surface p-2 shadow-[0_1px_0_rgba(0,0,0,0.02),0_24px_48px_-24px_rgba(22,21,26,0.18)]"
    >
      {/* Main URL row */}
      <div
        className={`flex flex-col gap-2 rounded-[22px] border bg-paper/60 p-2 transition sm:flex-row sm:items-center ${
          errors.url ? 'border-danger/60' : 'border-transparent focus-within:border-line-strong'
        }`}
      >
        <label htmlFor={ids.url} className="sr-only">
          Long URL
        </label>
        <input
          id={ids.url}
          type="url"
          inputMode="url"
          autoComplete="off"
          autoFocus
          spellCheck={false}
          placeholder="Paste a long link — https://…"
          value={url}
          onChange={(e) => {
            setUrl(e.target.value);
            if (errors.url) setErrors((prev) => ({ ...prev, url: undefined }));
          }}
          aria-invalid={Boolean(errors.url)}
          aria-describedby={errors.url ? `${ids.url}-error` : undefined}
          className="h-12 min-w-0 flex-1 bg-transparent px-3 text-base text-ink placeholder:text-muted focus:outline-none sm:text-[17px]"
        />
        <button
          type="submit"
          disabled={submitting}
          className="group inline-flex h-12 shrink-0 items-center justify-center gap-2 rounded-[16px] bg-accent px-6 text-[15px] font-medium text-accent-ink transition hover:brightness-105 active:scale-[0.98] disabled:cursor-wait disabled:opacity-70"
        >
          {submitting ? (
            <>
              <span className="size-4 animate-spin rounded-full border-2 border-current border-r-transparent" />
              Shortening
            </>
          ) : (
            <>
              Shorten
              <ArrowRightIcon className="transition group-hover:translate-x-0.5" />
            </>
          )}
        </button>
      </div>
      {errors.url && <FieldError id={`${ids.url}-error`} message={errors.url} className="px-4 pt-2" />}

      {/* Options toggle */}
      <div className="px-2 pt-1">
        <button
          type="button"
          onClick={() => setShowOptions((v) => !v)}
          aria-expanded={showOptions}
          className="inline-flex items-center gap-1.5 rounded-full px-2 py-2 text-[13px] font-medium text-ink-soft transition hover:text-ink"
        >
          <ChevronIcon className={`transition-transform ${showOptions ? 'rotate-180' : ''}`} width={14} height={14} />
          Custom alias &amp; expiry
          {!showOptions && (alias || preset !== 'never') && <span className="size-1.5 rounded-full bg-accent" />}
        </button>
      </div>

      {showOptions && (
        <div className="grid animate-rise gap-5 px-4 pb-4 pt-2 md:grid-cols-[1fr_1.4fr]">
          {/* Alias */}
          <div>
            <label htmlFor={ids.alias} className="mb-2 block text-[13px] font-medium text-ink-soft">
              Custom alias <span className="font-normal text-muted">(optional)</span>
            </label>
            <div
              className={`flex h-11 items-center overflow-hidden rounded-xl border bg-paper/60 font-mono text-sm transition focus-within:border-line-strong ${
                errors.customAlias ? 'border-danger/60' : 'border-line'
              }`}
            >
              <span className="select-none whitespace-nowrap pl-3 text-muted">{SHORT_HOST}/</span>
              <input
                id={ids.alias}
                value={alias}
                onChange={(e) => {
                  setAlias(e.target.value.replace(/\s/g, ''));
                  if (errors.customAlias) setErrors((prev) => ({ ...prev, customAlias: undefined }));
                }}
                maxLength={32}
                spellCheck={false}
                autoComplete="off"
                placeholder="my-link"
                aria-invalid={Boolean(errors.customAlias)}
                aria-describedby={`${ids.alias}-hint`}
                className="h-full min-w-0 flex-1 bg-transparent pr-3 text-ink placeholder:text-muted/60 focus:outline-none"
              />
            </div>
            {errors.customAlias ? (
              <FieldError id={`${ids.alias}-hint`} message={errors.customAlias} className="pt-2" />
            ) : (
              <p id={`${ids.alias}-hint`} className="pt-2 text-xs text-muted">
                3–32 letters, digits, - or _
              </p>
            )}
          </div>

          {/* Expiry */}
          <fieldset>
            <legend className="mb-2 block text-[13px] font-medium text-ink-soft">Expires</legend>
            <div className="flex flex-wrap gap-1.5" role="radiogroup">
              {EXPIRY_OPTIONS.map((option) => {
                const selected = preset === option.value;
                return (
                  <button
                    key={option.value}
                    type="button"
                    role="radio"
                    aria-checked={selected}
                    onClick={() => {
                      setPreset(option.value);
                      setErrors((prev) => ({ ...prev, expiresAt: undefined }));
                    }}
                    className={`h-9 rounded-full border px-3.5 text-[13px] font-medium transition ${
                      selected
                        ? 'border-ink bg-ink text-paper'
                        : 'border-line bg-paper/60 text-ink-soft hover:border-line-strong hover:text-ink'
                    }`}
                  >
                    {option.label}
                  </button>
                );
              })}
            </div>
            {preset === 'custom' && (
              <div className="mt-3 animate-rise">
                <label htmlFor={ids.expiry} className="sr-only">
                  Expiry date and time
                </label>
                <input
                  id={ids.expiry}
                  type="datetime-local"
                  value={customExpiry}
                  min={minLocal}
                  onChange={(e) => {
                    setCustomExpiry(e.target.value);
                    setErrors((prev) => ({ ...prev, expiresAt: undefined }));
                  }}
                  aria-invalid={Boolean(errors.expiresAt)}
                  className={`h-11 w-full rounded-xl border bg-paper/60 px-3 font-mono text-sm text-ink focus:border-line-strong focus:outline-none sm:w-auto ${
                    errors.expiresAt ? 'border-danger/60' : 'border-line'
                  }`}
                />
              </div>
            )}
            {errors.expiresAt && <FieldError message={errors.expiresAt} className="pt-2" />}
          </fieldset>
        </div>
      )}

      {formError && (
        <div role="alert" className="mx-2 mb-2 flex items-start gap-2 rounded-2xl bg-danger-soft px-4 py-3 text-sm text-danger">
          <AlertIcon className="mt-0.5 shrink-0" />
          {formError}
        </div>
      )}
    </form>
  );
}

function FieldError({ id, message, className = '' }: { id?: string; message: string; className?: string }) {
  return (
    <p id={id} role="alert" className={`flex items-center gap-1.5 text-[13px] text-danger ${className}`}>
      <AlertIcon width={14} height={14} className="shrink-0" />
      {message}
    </p>
  );
}
