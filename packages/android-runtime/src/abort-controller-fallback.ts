type AbortListener = EventListenerOrEventListenerObject;

interface AbortControllerHost {
  AbortController?: typeof AbortController;
}

/** Chrome 61 predates `globalThis`, while Android WebView always exposes `window`. */
export const resolveRuntimeGlobal = (): object => {
  if (typeof window !== "undefined") return window;
  if (typeof globalThis !== "undefined") return globalThis;
  throw new Error("AgentDock runtime global object is unavailable.");
};

/**
 * The runtime only needs the AbortSignal surface used by agent-core and the
 * native RPC client: `aborted` plus abort listeners. Some Android System
 * WebViews old enough to match our Chrome 61 bundle target do not expose the
 * browser AbortController global.
 */
class LightweightAbortSignal {
  public aborted = false;
  public reason: unknown = undefined;

  readonly #listeners = new Map<AbortListener, boolean>();

  public addEventListener(
    type: string,
    listener: AbortListener | null,
    options?: boolean | AddEventListenerOptions,
  ): void {
    if (type !== "abort" || listener === null || this.#listeners.has(listener)) {
      return;
    }
    const once = typeof options === "object" && options.once === true;
    this.#listeners.set(listener, once);
  }

  public removeEventListener(type: string, listener: AbortListener | null): void {
    if (type === "abort" && listener !== null) {
      this.#listeners.delete(listener);
    }
  }

  public throwIfAborted(): void {
    if (!this.aborted) return;
    throw this.reason;
  }

  public dispatchAbort(reason: unknown): void {
    if (this.aborted) return;
    this.aborted = true;
    this.reason = reason;
    const event = new Event("abort");
    for (const [listener, once] of [...this.#listeners]) {
      if (once) this.#listeners.delete(listener);
      try {
        if (typeof listener === "function") {
          listener.call(this as unknown as EventTarget, event);
        } else {
          listener.handleEvent(event);
        }
      } catch {
        // Abort observers are independent; one listener cannot prevent the
        // remaining pending Tool calls from being cancelled.
      }
    }
  }
}

class LightweightAbortController {
  readonly #mutableSignal = new LightweightAbortSignal();

  public readonly signal = this.#mutableSignal as unknown as AbortSignal;

  public abort(reason?: unknown): void {
    const fallbackReason = reason ?? new DOMException("This operation was aborted", "AbortError");
    this.#mutableSignal.dispatchAbort(fallbackReason);
  }
}

/** Installs the smallest AbortController implementation AgentDock needs. */
export const installAbortControllerFallback = (
  target: AbortControllerHost = resolveRuntimeGlobal() as AbortControllerHost,
): boolean => {
  if (typeof target.AbortController === "function") return false;
  target.AbortController = LightweightAbortController as unknown as typeof AbortController;
  return true;
};
