/** Pulsing "live" marker: has a text label too, so it isn't conveyed by motion or colour alone. */
export function LiveDot() {
  return (
    <span className="inline-flex items-center gap-1.5 text-[11px] font-medium uppercase tracking-wider text-ok">
      <span className="relative flex size-2">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-ok opacity-60 motion-reduce:hidden" />
        <span className="relative inline-flex size-2 rounded-full bg-ok" />
      </span>
      Live
    </span>
  );
}
