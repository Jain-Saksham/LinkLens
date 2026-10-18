import { useEffect, useState } from 'react';
import { CheckIcon, CopyIcon } from './icons';

interface Props {
  text: string;
  variant?: 'solid' | 'ghost';
  label?: string;
}

export function CopyButton({ text, variant = 'ghost', label }: Props) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(false), 1600);
    return () => clearTimeout(timer);
  }, [copied]);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
    } catch {
      // Clipboard API blocked (non-secure origin); fall back to a prompt the user can copy from.
      window.prompt('Copy this link:', text);
    }
  }

  const Icon = copied ? CheckIcon : CopyIcon;

  if (variant === 'solid') {
    return (
      <button
        type="button"
        onClick={copy}
        className="inline-flex h-11 items-center gap-2 rounded-full bg-ink px-5 text-sm font-medium text-paper transition hover:opacity-90 active:scale-[0.98]"
      >
        <Icon />
        <span aria-live="polite">{copied ? 'Copied' : (label ?? 'Copy')}</span>
      </button>
    );
  }

  return (
    <button
      type="button"
      onClick={copy}
      aria-label={copied ? 'Copied' : `Copy ${text}`}
      title={copied ? 'Copied' : 'Copy'}
      className={`grid size-8 place-items-center rounded-full transition hover:bg-surface-sunk ${copied ? 'text-ok' : 'text-muted hover:text-ink'}`}
    >
      <Icon />
    </button>
  );
}
