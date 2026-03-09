declare function acquireVsCodeApi(): { postMessage(msg: unknown): void };

interface Bridge {
  postMessage(msg: unknown): void;
}

declare global {
  interface Window {
    __pixelAgentsBridge?: { postMessage(msg: string): void };
    __onHostMessage?: (json: string) => void;
  }
}

const pendingMessages: string[] = [];

function createBridge(): Bridge {
  if (typeof acquireVsCodeApi === 'function') {
    return acquireVsCodeApi();
  }



  return {
    postMessage: (msg: unknown) => {
      const json = JSON.stringify(msg);
      if (window.__pixelAgentsBridge) {
        window.__pixelAgentsBridge.postMessage(json);
      } else {
        pendingMessages.push(json);
      }
    },
  };
}

window.__onHostMessage = (json: string) => {
  try {
    const data = JSON.parse(json);
    window.dispatchEvent(new MessageEvent('message', { data }));
  } catch (e) {
    console.error('[PixelAgents] Failed to parse host message:', e);
  }
};

if (typeof acquireVsCodeApi !== 'function') {
  const flushInterval = setInterval(() => {
    if (window.__pixelAgentsBridge) {
      clearInterval(flushInterval);
      for (const msg of pendingMessages) {
        window.__pixelAgentsBridge.postMessage(msg);
      }
      pendingMessages.length = 0;
    }
  }, 10);

  setTimeout(() => clearInterval(flushInterval), 10000);
}

export const vscode = createBridge();
