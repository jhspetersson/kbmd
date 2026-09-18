// Glue for the Android app, which shows this UI in a WebView. In a browser none of it is active.

export interface NativeHooks {
  /** The back gesture: closes whatever is on top. False when there was nothing to close. */
  back: () => boolean;
  /** The app is going to the background: save now. */
  flush: () => void;
  /** Launcher shortcuts and content shared from other apps. */
  command: (name: 'open' | 'new-note' | 'daily' | 'search' | 'refresh', argument: string | null) => void;
}

declare global {
  interface Window {
    /** Injected by the Android app. */
    KbmdNative?: {
      setTheme: (dark: boolean) => void;
      ready: () => void;
    };
    __kbmd?: NativeHooks;
  }
}

export const isNative = typeof window !== 'undefined' && window.KbmdNative !== undefined;

/** Phone-sized window: side panels become overlays (see mobile.css). */
export const isNarrow = () => window.matchMedia('(max-width: 760px)').matches;

/** Finger or pen rather than a mouse. */
export const isTouch = () => window.matchMedia('(pointer: coarse)').matches;
