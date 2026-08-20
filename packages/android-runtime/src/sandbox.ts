import ts from "typescript";

import {
  toolFailure,
  toolSuccess,
  type AgentTool,
  type JsonSchema,
  type JsonValue,
  type ToolResult
} from "@pocketpilot/tool-runtime";

export type SandboxLanguage = "javascript" | "typescript";
export type SandboxConsoleLevel = "log" | "info" | "warn" | "error";

export interface SandboxConsoleEntry {
  readonly level: SandboxConsoleLevel;
  readonly arguments: readonly JsonValue[];
}

export interface SandboxExecutionRequest {
  readonly language: SandboxLanguage;
  /**
   * An async function body. The body can read `input`, use the bounded
   * `console`, use top-level await, and should return a JSON-safe value.
   */
  readonly source: string;
  readonly input?: JsonValue;
  readonly timeoutMillis?: number;
}

export interface SandboxExecutionData {
  readonly language: SandboxLanguage;
  readonly value: JsonValue;
  readonly console: readonly SandboxConsoleEntry[];
  readonly consoleTruncated: boolean;
  readonly durationMillis: number;
}

export type SandboxWorker = Pick<
  Worker,
  "onmessage" | "onerror" | "onmessageerror" | "postMessage" | "terminate"
>;

export type SandboxWorkerFactory = (workerSource: string) => SandboxWorker;

export interface WorkerSandboxOptions {
  readonly workerFactory?: SandboxWorkerFactory;
  readonly defaultTimeoutMillis?: number;
}

export const SANDBOX_LIMITS = Object.freeze({
  maxSourceBytes: 128 * 1_024,
  maxInputBytes: 128 * 1_024,
  maxOutputBytes: 256 * 1_024,
  maxConsoleBytes: 64 * 1_024,
  maxConsoleEntries: 100,
  maxConsoleEntryBytes: 4 * 1_024,
  maxConcurrentExecutions: 4,
  minTimeoutMillis: 100,
  maxTimeoutMillis: 30_000,
  defaultTimeoutMillis: 5_000
});

const MAX_RESPONSE_BYTES =
  SANDBOX_LIMITS.maxOutputBytes + SANDBOX_LIMITS.maxConsoleBytes + 16 * 1_024;
const MAX_ERROR_MESSAGE_CHARS = 2_048;

interface WorkerExecutionRequest {
  readonly inputJson: string;
  readonly maxOutputBytes: number;
  readonly maxConsoleBytes: number;
  readonly maxConsoleEntries: number;
  readonly maxConsoleEntryBytes: number;
}

interface WorkerSuccessResponse {
  readonly success: true;
  readonly value: JsonValue;
  readonly console: readonly SandboxConsoleEntry[];
  readonly consoleTruncated: boolean;
  readonly durationMillis: number;
}

interface WorkerFailureResponse {
  readonly success: false;
  readonly error: {
    readonly code: string;
    readonly message: string;
    readonly details?: JsonValue;
  };
}

type WorkerResponse = WorkerSuccessResponse | WorkerFailureResponse;

interface ActiveExecution {
  readonly worker: SandboxWorker;
  finish(result: ToolResult<SandboxExecutionData>): void;
}

const objectSchema = (
  properties: Readonly<Record<string, JsonSchema>>,
  required: readonly string[],
): JsonSchema => ({
  type: "object",
  properties,
  required,
  additionalProperties: false
});

const scriptToolSchema = objectSchema(
  {
    source: {
      type: "string",
      minLength: 1,
      maxLength: SANDBOX_LIMITS.maxSourceBytes,
      description:
        "Async function body. Read the JSON input variable, optionally log, and return a JSON value."
    },
    input: {
      type: ["object", "array", "string", "number", "boolean", "null"],
      description: "JSON value exposed to the script as input."
    },
    timeoutMillis: {
      type: "integer",
      minimum: SANDBOX_LIMITS.minTimeoutMillis,
      maximum: SANDBOX_LIMITS.maxTimeoutMillis,
      default: SANDBOX_LIMITS.defaultTimeoutMillis
    }
  },
  ["source"],
);

const byteLength = (value: string): number =>
  new TextEncoder().encode(value).byteLength;

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const truncatedMessage = (value: unknown): string => {
  const message = value instanceof Error ? value.message : String(value);
  return message.slice(0, MAX_ERROR_MESSAGE_CHARS);
};

const jsonValueError = (value: unknown, path: string, seen: WeakSet<object>): string | undefined => {
  if (value === null || typeof value === "string" || typeof value === "boolean") {
    return undefined;
  }
  if (typeof value === "number") {
    return Number.isFinite(value) ? undefined : `${path} contains a non-finite number.`;
  }
  if (typeof value !== "object") {
    return `${path} contains a value that JSON cannot represent.`;
  }
  if (seen.has(value)) {
    return `${path} contains a repeated or cyclic object reference.`;
  }
  seen.add(value);
  if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index += 1) {
      const error = jsonValueError(value[index], `${path}[${index}]`, seen);
      if (error !== undefined) return error;
    }
    return undefined;
  }
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) {
    return `${path} contains a non-JSON object.`;
  }
  for (const key of Object.keys(value)) {
    const error = jsonValueError(
      (value as Record<string, unknown>)[key],
      `${path}.${key}`,
      seen,
    );
    if (error !== undefined) return error;
  }
  return undefined;
};

const serializeJsonInput = (
  input: JsonValue | undefined,
): ToolResult<{ readonly json: string }> => {
  const normalized = input ?? null;
  let validationError: string | undefined;
  try {
    validationError = jsonValueError(normalized, "input", new WeakSet());
  } catch (error) {
    return toolFailure("INVALID_INPUT", `Input validation failed: ${truncatedMessage(error)}`);
  }
  if (validationError !== undefined) {
    return toolFailure("INVALID_INPUT", validationError);
  }
  let json: string;
  try {
    json = JSON.stringify(normalized);
  } catch (error) {
    return toolFailure("INVALID_INPUT", `Input is not serializable: ${truncatedMessage(error)}`);
  }
  if (byteLength(json) > SANDBOX_LIMITS.maxInputBytes) {
    return toolFailure(
      "INPUT_LIMIT_EXCEEDED",
      `Serialized input exceeds ${SANDBOX_LIMITS.maxInputBytes} UTF-8 bytes.`,
    );
  }
  return toolSuccess({ json });
};

const sourceFileName = (language: SandboxLanguage): string =>
  language === "typescript" ? "sandbox.ts" : "sandbox.js";

const sourceScriptKind = (language: SandboxLanguage): ts.ScriptKind =>
  language === "typescript" ? ts.ScriptKind.TS : ts.ScriptKind.JS;

const wrappedSource = (source: string, language: SandboxLanguage): string =>
  language === "typescript"
    ? `async function __pocketpilot_main(input: unknown): Promise<unknown> {\n${source}\n}`
    : `async function __pocketpilot_main(input) {\n${source}\n}`;

const forbiddenSyntax = (
  source: string,
  language: SandboxLanguage,
): string | undefined => {
  const file = ts.createSourceFile(
    sourceFileName(language),
    wrappedSource(source, language),
    ts.ScriptTarget.ES2020,
    true,
    sourceScriptKind(language),
  );
  const onlyStatement = file.statements[0];
  if (
    file.statements.length !== 1 ||
    onlyStatement === undefined ||
    !ts.isFunctionDeclaration(onlyStatement) ||
    onlyStatement.name?.text !== "__pocketpilot_main" ||
    onlyStatement.body === undefined
  ) {
    return "Source must remain inside the sandbox function body.";
  }
  let failure: string | undefined;
  const visit = (node: ts.Node): void => {
    if (failure !== undefined) return;
    if (
      ts.isImportDeclaration(node) ||
      ts.isImportEqualsDeclaration(node) ||
      ts.isExportDeclaration(node) ||
      ts.isExportAssignment(node)
    ) {
      failure = "Module import and export syntax is unavailable in the sandbox.";
      return;
    }
    if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword) {
      failure = "Dynamic import is unavailable in the sandbox.";
      return;
    }
    if (
      (ts.isFunctionDeclaration(node) ||
        ts.isFunctionExpression(node) ||
        ts.isMethodDeclaration(node)) &&
      node.asteriskToken !== undefined &&
      node.modifiers?.some(({ kind }) => kind === ts.SyntaxKind.AsyncKeyword) === true
    ) {
      failure = "Async generators are unavailable in the Chrome 61 sandbox target.";
      return;
    }
    ts.forEachChild(node, visit);
  };
  visit(file);
  return failure;
};

const diagnosticMessage = (diagnostic: ts.Diagnostic): string => {
  const message = ts.flattenDiagnosticMessageText(diagnostic.messageText, "\n");
  if (diagnostic.file === undefined || diagnostic.start === undefined) {
    return message;
  }
  const position = diagnostic.file.getLineAndCharacterOfPosition(diagnostic.start);
  return `${position.line + 1}:${position.character + 1} ${message}`;
};

const prepareScript = (
  source: string,
  language: SandboxLanguage,
): ToolResult<{ readonly script: string }> => {
  const syntaxFailure = forbiddenSyntax(source, language);
  if (syntaxFailure !== undefined) {
    return toolFailure("UNSUPPORTED_SYNTAX", syntaxFailure);
  }
  if (language === "javascript") {
    return toolSuccess({ script: wrappedSource(source, language) });
  }
  const result = ts.transpileModule(wrappedSource(source, language), {
    fileName: sourceFileName(language),
    reportDiagnostics: true,
    compilerOptions: {
      target: ts.ScriptTarget.ES2017,
      module: ts.ModuleKind.None,
      isolatedModules: true,
      removeComments: true,
      sourceMap: false,
      inlineSourceMap: false,
      importHelpers: false
    }
  });
  const errors = (result.diagnostics ?? [])
    .filter(({ category }) => category === ts.DiagnosticCategory.Error)
    .slice(0, 8)
    .map(diagnosticMessage);
  if (errors.length > 0) {
    return toolFailure("TRANSPILE_FAILED", "TypeScript could not be transpiled.", {
      details: { diagnostics: errors }
    });
  }
  return toolSuccess({ script: result.outputText });
};

const parseToolInput = (
  value: unknown,
  language: SandboxLanguage,
): ToolResult<SandboxExecutionRequest> => {
  if (!isRecord(value)) {
    return toolFailure("INVALID_INPUT", "Tool input must be an object.");
  }
  const allowedKeys = new Set(["source", "input", "timeoutMillis"]);
  const unsupported = Object.keys(value).find((key) => !allowedKeys.has(key));
  if (unsupported !== undefined) {
    return toolFailure("INVALID_INPUT", `Unsupported input field: ${unsupported}`);
  }
  if (typeof value.source !== "string" || value.source.length === 0) {
    return toolFailure("INVALID_INPUT", "source must be a non-empty string.");
  }
  if (byteLength(value.source) > SANDBOX_LIMITS.maxSourceBytes) {
    return toolFailure(
      "SOURCE_LIMIT_EXCEEDED",
      `Source exceeds ${SANDBOX_LIMITS.maxSourceBytes} UTF-8 bytes.`,
    );
  }
  const timeoutMillis = value.timeoutMillis ?? SANDBOX_LIMITS.defaultTimeoutMillis;
  if (
    typeof timeoutMillis !== "number" ||
    !Number.isSafeInteger(timeoutMillis) ||
    timeoutMillis < SANDBOX_LIMITS.minTimeoutMillis ||
    timeoutMillis > SANDBOX_LIMITS.maxTimeoutMillis
  ) {
    return toolFailure(
      "INVALID_INPUT",
      `timeoutMillis must be an integer between ${SANDBOX_LIMITS.minTimeoutMillis} and ${SANDBOX_LIMITS.maxTimeoutMillis}.`,
    );
  }
  return toolSuccess({
    language,
    source: value.source,
    ...(value.input === undefined ? {} : { input: value.input as JsonValue }),
    timeoutMillis
  });
};

const isConsoleEntry = (value: unknown): value is SandboxConsoleEntry => {
  if (!isRecord(value)) return false;
  if (
    value.level !== "log" &&
    value.level !== "info" &&
    value.level !== "warn" &&
    value.level !== "error"
  ) {
    return false;
  }
  return Array.isArray(value.arguments) &&
    jsonValueError(value.arguments, "console.arguments", new WeakSet()) === undefined;
};

const parseWorkerResponse = (value: unknown): WorkerResponse | undefined => {
  if (!isRecord(value) || typeof value.success !== "boolean") return undefined;
  if (value.success === false) {
    if (!isRecord(value.error)) return undefined;
    const { code, message, details } = value.error;
    if (typeof code !== "string" || typeof message !== "string") return undefined;
    if (
      details !== undefined &&
      jsonValueError(details, "error.details", new WeakSet()) !== undefined
    ) {
      return undefined;
    }
    return {
      success: false,
      error: {
        code,
        message: message.slice(0, MAX_ERROR_MESSAGE_CHARS),
        ...(details === undefined ? {} : { details: details as JsonValue })
      }
    };
  }
  if (
    !Array.isArray(value.console) ||
    value.console.length > SANDBOX_LIMITS.maxConsoleEntries ||
    !value.console.every(isConsoleEntry) ||
    typeof value.consoleTruncated !== "boolean" ||
    typeof value.durationMillis !== "number" ||
    !Number.isFinite(value.durationMillis) ||
    value.durationMillis < 0 ||
    jsonValueError(value.value, "value", new WeakSet()) !== undefined
  ) {
    return undefined;
  }
  let serializedValue: string;
  let serializedConsole: string;
  try {
    serializedValue = JSON.stringify(value.value);
    serializedConsole = JSON.stringify(value.console);
  } catch {
    return undefined;
  }
  if (
    byteLength(serializedValue) > SANDBOX_LIMITS.maxOutputBytes ||
    byteLength(serializedConsole) > SANDBOX_LIMITS.maxConsoleBytes ||
    value.console.some(
      (entry) => byteLength(JSON.stringify(entry)) > SANDBOX_LIMITS.maxConsoleEntryBytes,
    )
  ) {
    return undefined;
  }
  return {
    success: true,
    value: value.value as JsonValue,
    console: value.console,
    consoleTruncated: value.consoleTruncated,
    durationMillis: value.durationMillis
  };
};

const defaultWorkerFactory: SandboxWorkerFactory = (workerSource) => {
  if (
    typeof Worker !== "function" ||
    typeof Blob !== "function" ||
    typeof URL !== "function" ||
    typeof URL.createObjectURL !== "function" ||
    typeof URL.revokeObjectURL !== "function"
  ) {
    throw new Error("This runtime does not support isolated Web Workers.");
  }
  const url = URL.createObjectURL(new Blob([workerSource], { type: "text/javascript" }));
  let worker: Worker;
  try {
    worker = new Worker(url, { name: "pocketpilot-script-sandbox" });
  } catch (error) {
    URL.revokeObjectURL(url);
    throw error;
  }
  let revoked = false;
  const revoke = (): void => {
    if (revoked) return;
    revoked = true;
    URL.revokeObjectURL(url);
  };
  return {
    get onmessage() { return worker.onmessage; },
    set onmessage(handler) { worker.onmessage = handler; },
    get onerror() { return worker.onerror; },
    set onerror(handler) { worker.onerror = handler; },
    get onmessageerror() { return worker.onmessageerror; },
    set onmessageerror(handler) { worker.onmessageerror = handler; },
    postMessage: (message) => worker.postMessage(message),
    terminate: () => {
      worker.terminate();
      revoke();
    }
  };
};

/**
 * Self-contained classic-worker program. It intentionally has no dependency on
 * the app bundle: a fresh Blob Worker gets neither DOM access nor the WebView's
 * PocketPilotNativeBridge object. Every message crossing the boundary is JSON
 * text and every worker is terminated after exactly one execution.
 */
export const SANDBOX_WORKER_SOURCE = String.raw`
"use strict";
async function __pocketpilot_user_entry(input, console) {
  "use strict";
  /*__POCKETPILOT_USER_SCRIPT__*/
  return await __pocketpilot_main(input);
}
(function (userEntry) {
  var scope = self;
  var safePostMessage = scope.postMessage.bind(scope);
  var safeAddEventListener = scope.addEventListener.bind(scope);
  var safeParse = JSON.parse.bind(JSON);
  var safeStringify = JSON.stringify.bind(JSON);
  var safeNow = Date.now.bind(Date);
  var encoder = new TextEncoder();
  var safeEncode = encoder.encode.bind(encoder);
  var safeArrayIsArray = Array.isArray.bind(Array);
  var safeObjectKeys = Object.keys.bind(Object);
  var safeGetPrototypeOf = Object.getPrototypeOf.bind(Object);
  var safeGetOwnPropertyDescriptor = Object.getOwnPropertyDescriptor.bind(Object);
  var safeDefineProperty = Object.defineProperty.bind(Object);
  var NativeFunction = Function;
  var safeArrayPush = NativeFunction.call.bind(Array.prototype.push);
  var safeStringSlice = NativeFunction.call.bind(String.prototype.slice);
  var safeNumberIsFinite = Number.isFinite.bind(Number);
  var safeMathMin = Math.min.bind(Math);
  var safeMathMax = Math.max.bind(Math);
  var SafeWeakSet = WeakSet;
  var safeWeakSetHas = NativeFunction.call.bind(SafeWeakSet.prototype.has);
  var safeWeakSetAdd = NativeFunction.call.bind(SafeWeakSet.prototype.add);
  var AsyncFunction = safeGetPrototypeOf(async function () {}).constructor;
  var GeneratorFunction = safeGetPrototypeOf(function* () {}).constructor;

  function bytes(value) {
    return safeEncode(value).byteLength;
  }

  function message(error) {
    var text;
    try {
      text = error && typeof error.message === "string" ? error.message : String(error);
    } catch (_) {
      text = "Unknown script error.";
    }
    return safeStringSlice(text, 0, 2048);
  }

  function fail(code, text, details) {
    var error = { code: code, message: safeStringSlice(String(text), 0, 2048) };
    if (details !== undefined) error.details = details;
    return { success: false, error: error };
  }

  function send(response) {
    try {
      safePostMessage(safeStringify(response));
    } catch (error) {
      safePostMessage(safeStringify(fail("SANDBOX_SERIALIZATION_FAILED", message(error))));
    }
  }

  function eraseDescriptor(target, name) {
    var descriptor = safeGetOwnPropertyDescriptor(target, name);
    if (descriptor === undefined) return;
    try {
      if (descriptor.configurable) {
        safeDefineProperty(target, name, {
          value: undefined,
          writable: false,
          configurable: false,
          enumerable: descriptor.enumerable
        });
      } else if ("value" in descriptor && (descriptor.value === undefined || descriptor.writable)) {
        safeDefineProperty(target, name, { value: undefined, writable: false });
      } else {
        throw new Error("Capability descriptor is immutable.");
      }
    } catch (_) {
      throw new Error("Unable to erase worker capability descriptor: " + name);
    }
    var locked = safeGetOwnPropertyDescriptor(target, name);
    if (locked === undefined || !("value" in locked) || locked.value !== undefined ||
        locked.writable || locked.configurable || locked.get !== undefined || locked.set !== undefined) {
      throw new Error("Worker capability descriptor remained recoverable: " + name);
    }
  }

  function lockGlobal(name) {
    var target = scope;
    while (target !== null) {
      eraseDescriptor(target, name);
      target = safeGetPrototypeOf(target);
    }
    if (safeGetOwnPropertyDescriptor(scope, name) === undefined) {
      try {
        safeDefineProperty(scope, name, {
          value: undefined,
          writable: false,
          configurable: false,
          enumerable: false
        });
      } catch (_) {
        throw new Error("Unable to shadow worker capability: " + name);
      }
    }
    if (typeof scope[name] !== "undefined") {
      throw new Error("Unable to disable worker capability: " + name);
    }
  }

  function lockConstructor(constructorValue) {
    if (!constructorValue || !constructorValue.prototype) return;
    try {
      safeDefineProperty(constructorValue.prototype, "constructor", {
        value: undefined,
        writable: false,
        configurable: false
      });
    } catch (_) {
      throw new Error("Unable to disable dynamic code construction.");
    }
  }

  function jsonError(value, path, seen) {
    if (value === null || typeof value === "string" || typeof value === "boolean") return undefined;
    if (typeof value === "number") {
      return safeNumberIsFinite(value) ? undefined : path + " contains a non-finite number.";
    }
    if (typeof value !== "object") return path + " contains a value that JSON cannot represent.";
    if (safeWeakSetHas(seen, value)) return path + " contains a repeated or cyclic object reference.";
    safeWeakSetAdd(seen, value);
    if (safeArrayIsArray(value)) {
      for (var index = 0; index < value.length; index += 1) {
        var arrayFailure = jsonError(value[index], path + "[" + index + "]", seen);
        if (arrayFailure !== undefined) return arrayFailure;
      }
      return undefined;
    }
    var prototype = safeGetPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) return path + " contains a non-JSON object.";
    var keys = safeObjectKeys(value);
    for (var keyIndex = 0; keyIndex < keys.length; keyIndex += 1) {
      var key = keys[keyIndex];
      var objectFailure = jsonError(value[key], path + "." + key, seen);
      if (objectFailure !== undefined) return objectFailure;
    }
    return undefined;
  }

  function consoleValue(value, depth, seen) {
    if (value === null || typeof value === "boolean") return value;
    if (typeof value === "string") return value.length <= 2048 ? value : safeStringSlice(value, 0, 2048) + "...[truncated]";
    if (typeof value === "number") return safeNumberIsFinite(value) ? value : String(value);
    if (typeof value === "undefined") return "[undefined]";
    if (typeof value === "bigint") return String(value) + "n";
    if (typeof value === "symbol") return String(value);
    if (typeof value === "function") return "[Function]";
    if (depth >= 4) return "[Max depth]";
    if (safeWeakSetHas(seen, value)) return "[Circular]";
    safeWeakSetAdd(seen, value);
    if (safeArrayIsArray(value)) {
      var array = [];
      var arrayLength = safeMathMin(value.length, 20);
      for (var index = 0; index < arrayLength; index += 1) {
        safeArrayPush(array, consoleValue(value[index], depth + 1, seen));
      }
      if (value.length > arrayLength) safeArrayPush(array, "...[truncated]");
      return array;
    }
    var result = {};
    var keys;
    try { keys = safeObjectKeys(value); } catch (_) { return "[Uninspectable object]"; }
    var objectLength = safeMathMin(keys.length, 20);
    for (var keyIndex = 0; keyIndex < objectLength; keyIndex += 1) {
      var key = safeStringSlice(keys[keyIndex], 0, 256);
      try {
        result[key] = consoleValue(value[keys[keyIndex]], depth + 1, seen);
      } catch (_) {
        result[key] = "[Unreadable]";
      }
    }
    if (keys.length > objectLength) result["..."] = "[truncated]";
    return result;
  }

  var initializationFailure;
  try {
    var blocked = [
      "fetch", "XMLHttpRequest", "WebSocket", "EventSource", "WebTransport",
      "WebSocketStream", "RTCPeerConnection", "webkitRTCPeerConnection",
      "importScripts", "Worker", "SharedWorker", "BroadcastChannel",
      "indexedDB", "caches", "open", "close", "postMessage",
      "navigator", "Notification",
      "PocketPilotNativeBridge", "PocketPilotRuntime",
      "showOpenFilePicker", "showSaveFilePicker", "showDirectoryPicker",
      "process", "require", "module"
    ];
    for (var blockedIndex = 0; blockedIndex < blocked.length; blockedIndex += 1) {
      lockGlobal(blocked[blockedIndex]);
    }
    lockGlobal("__pocketpilot_user_entry");
    lockGlobal("eval");
    lockGlobal("Function");
    lockConstructor(NativeFunction);
    lockConstructor(AsyncFunction);
    lockConstructor(GeneratorFunction);
  } catch (error) {
    initializationFailure = fail("SANDBOX_INITIALIZATION_FAILED", message(error));
  }

  safeAddEventListener("message", async function (event) {
    if (initializationFailure !== undefined) {
      send(initializationFailure);
      return;
    }
    var request;
    try {
      if (typeof event.data !== "string") throw new Error("Worker request must be JSON text.");
      request = safeParse(event.data);
      if (!request || typeof request !== "object" || typeof request.inputJson !== "string") {
        throw new Error("Worker request has an invalid shape.");
      }
    } catch (error) {
      send(fail("SANDBOX_PROTOCOL_ERROR", message(error)));
      return;
    }

    var entries = [];
    var consoleBytes = 0;
    var consoleTruncated = false;
    function capture(level, values) {
      if (entries.length >= request.maxConsoleEntries) {
        consoleTruncated = true;
        return;
      }
      var converted = [];
      for (var index = 0; index < values.length; index += 1) {
        safeArrayPush(converted, consoleValue(values[index], 0, new SafeWeakSet()));
      }
      var entry = { level: level, arguments: converted };
      var serialized;
      try { serialized = safeStringify(entry); } catch (_) { serialized = ""; }
      if (bytes(serialized) > request.maxConsoleEntryBytes) {
        entry = { level: level, arguments: ["[console entry exceeded byte limit]"] };
        serialized = safeStringify(entry);
        consoleTruncated = true;
      }
      var entryBytes = bytes(serialized);
      if (consoleBytes + entryBytes > request.maxConsoleBytes) {
        consoleTruncated = true;
        return;
      }
      consoleBytes += entryBytes;
      safeArrayPush(entries, entry);
    }
    var sandboxConsole = Object.freeze({
      log: function () { capture("log", arguments); },
      info: function () { capture("info", arguments); },
      warn: function () { capture("warn", arguments); },
      error: function () { capture("error", arguments); }
    });
    try {
      safeDefineProperty(scope, "console", {
        value: sandboxConsole,
        writable: false,
        configurable: false
      });
    } catch (_) {}

    var input;
    try {
      input = safeParse(request.inputJson);
    } catch (error) {
      send(fail("SANDBOX_PROTOCOL_ERROR", "Worker input is not valid JSON."));
      return;
    }
    var startedAt = safeNow();
    var value;
    try {
      value = await userEntry(input, sandboxConsole);
    } catch (error) {
      var compileError = error && error.name === "SyntaxError";
      send(fail(compileError ? "SCRIPT_COMPILE_ERROR" : "SCRIPT_RUNTIME_ERROR", message(error), {
        name: error && typeof error.name === "string" ? error.name.slice(0, 256) : "Error"
      }));
      return;
    }
    var resultFailure;
    try {
      resultFailure = jsonError(value, "result", new SafeWeakSet());
    } catch (error) {
      send(fail("SCRIPT_RESULT_INVALID", message(error)));
      return;
    }
    if (resultFailure !== undefined) {
      send(fail("SCRIPT_RESULT_INVALID", resultFailure));
      return;
    }
    var serializedValue;
    try { serializedValue = safeStringify(value); } catch (error) {
      send(fail("SCRIPT_RESULT_INVALID", message(error)));
      return;
    }
    if (bytes(serializedValue) > request.maxOutputBytes) {
      send(fail("OUTPUT_LIMIT_EXCEEDED", "Script result exceeds " + request.maxOutputBytes + " UTF-8 bytes."));
      return;
    }
    send({
      success: true,
      value: value,
      console: entries,
      consoleTruncated: consoleTruncated,
      durationMillis: safeMathMax(0, safeNow() - startedAt)
    });
  });
})(__pocketpilot_user_entry);
`;

const workerSourceFor = (script: string): string => {
  const marker = "/*__POCKETPILOT_USER_SCRIPT__*/";
  const markerIndex = SANDBOX_WORKER_SOURCE.indexOf(marker);
  if (markerIndex < 0) {
    throw new Error("Sandbox Worker template is missing its source marker.");
  }
  return SANDBOX_WORKER_SOURCE.slice(0, markerIndex) +
    script +
    SANDBOX_WORKER_SOURCE.slice(markerIndex + marker.length);
};

export class WorkerSandbox {
  readonly #workerFactory: SandboxWorkerFactory;
  readonly #defaultTimeoutMillis: number;
  readonly #active = new Set<ActiveExecution>();
  #closed = false;

  public constructor(options: WorkerSandboxOptions = {}) {
    this.#workerFactory = options.workerFactory ?? defaultWorkerFactory;
    this.#defaultTimeoutMillis =
      options.defaultTimeoutMillis ?? SANDBOX_LIMITS.defaultTimeoutMillis;
    if (
      !Number.isSafeInteger(this.#defaultTimeoutMillis) ||
      this.#defaultTimeoutMillis < SANDBOX_LIMITS.minTimeoutMillis ||
      this.#defaultTimeoutMillis > SANDBOX_LIMITS.maxTimeoutMillis
    ) {
      throw new Error(
        `defaultTimeoutMillis must be between ${SANDBOX_LIMITS.minTimeoutMillis} and ${SANDBOX_LIMITS.maxTimeoutMillis}.`,
      );
    }
  }

  public async execute(
    request: SandboxExecutionRequest,
    signal?: AbortSignal,
  ): Promise<ToolResult<SandboxExecutionData>> {
    if (this.#closed) {
      return toolFailure("SANDBOX_CLOSED", "The script sandbox is closed.");
    }
    if (signal?.aborted === true) {
      return toolFailure("CANCELLED", "Script execution was cancelled.");
    }
    if (this.#active.size >= SANDBOX_LIMITS.maxConcurrentExecutions) {
      return toolFailure(
        "SANDBOX_BUSY",
        `At most ${SANDBOX_LIMITS.maxConcurrentExecutions} sandbox executions may run concurrently.`,
      );
    }
    if (request.language !== "javascript" && request.language !== "typescript") {
      return toolFailure("INVALID_INPUT", "language must be javascript or typescript.");
    }
    if (typeof request.source !== "string" || request.source.length === 0) {
      return toolFailure("INVALID_INPUT", "source must be a non-empty string.");
    }
    if (byteLength(request.source) > SANDBOX_LIMITS.maxSourceBytes) {
      return toolFailure(
        "SOURCE_LIMIT_EXCEEDED",
        `Source exceeds ${SANDBOX_LIMITS.maxSourceBytes} UTF-8 bytes.`,
      );
    }
    const timeoutMillis = request.timeoutMillis ?? this.#defaultTimeoutMillis;
    if (
      !Number.isSafeInteger(timeoutMillis) ||
      timeoutMillis < SANDBOX_LIMITS.minTimeoutMillis ||
      timeoutMillis > SANDBOX_LIMITS.maxTimeoutMillis
    ) {
      return toolFailure(
        "INVALID_INPUT",
        `timeoutMillis must be an integer between ${SANDBOX_LIMITS.minTimeoutMillis} and ${SANDBOX_LIMITS.maxTimeoutMillis}.`,
      );
    }
    const serializedInput = serializeJsonInput(request.input);
    if (!serializedInput.success) return serializedInput;
    const prepared = prepareScript(request.source, request.language);
    if (!prepared.success) return prepared;

    let worker: SandboxWorker;
    try {
      worker = this.#workerFactory(workerSourceFor(prepared.data.script));
    } catch (error) {
      return toolFailure(
        "SANDBOX_UNAVAILABLE",
        `Unable to create an isolated Worker: ${truncatedMessage(error)}`,
      );
    }

    return new Promise((resolve) => {
      let settled = false;
      let timeoutHandle: ReturnType<typeof setTimeout> | undefined;
      const execution: ActiveExecution = {
        worker,
        finish: (result) => {
          if (settled) return;
          settled = true;
          if (timeoutHandle !== undefined) clearTimeout(timeoutHandle);
          signal?.removeEventListener("abort", onAbort);
          worker.onmessage = null;
          worker.onerror = null;
          worker.onmessageerror = null;
          worker.terminate();
          this.#active.delete(execution);
          resolve(result);
        }
      };
      const onAbort = (): void => {
        execution.finish(toolFailure("CANCELLED", "Script execution was cancelled."));
      };
      worker.onmessage = (event) => {
        if (typeof event.data !== "string") {
          execution.finish(
            toolFailure("SANDBOX_RESPONSE_INVALID", "Worker response must be JSON text."),
          );
          return;
        }
        if (byteLength(event.data) > MAX_RESPONSE_BYTES) {
          execution.finish(
            toolFailure("SANDBOX_RESPONSE_INVALID", "Worker response exceeded its byte budget."),
          );
          return;
        }
        let parsed: unknown;
        try {
          parsed = JSON.parse(event.data);
        } catch {
          execution.finish(
            toolFailure("SANDBOX_RESPONSE_INVALID", "Worker response is not valid JSON."),
          );
          return;
        }
        const response = parseWorkerResponse(parsed);
        if (response === undefined) {
          execution.finish(
            toolFailure("SANDBOX_RESPONSE_INVALID", "Worker response has an invalid shape."),
          );
          return;
        }
        if (!response.success) {
          execution.finish(
            toolFailure(response.error.code, response.error.message, {
              ...(response.error.details === undefined
                ? {}
                : { details: response.error.details })
            }),
          );
          return;
        }
        execution.finish(
          toolSuccess({
            language: request.language,
            value: response.value,
            console: response.console,
            consoleTruncated: response.consoleTruncated,
            durationMillis: response.durationMillis
          }),
        );
      };
      worker.onerror = (event) => {
        event.preventDefault?.();
        execution.finish(
          toolFailure(
            "WORKER_FAILED",
            `Isolated Worker failed: ${(event.message ?? "unknown error").slice(0, MAX_ERROR_MESSAGE_CHARS)}`,
          ),
        );
      };
      worker.onmessageerror = () => {
        execution.finish(
          toolFailure("SANDBOX_RESPONSE_INVALID", "Worker response could not be cloned."),
        );
      };
      this.#active.add(execution);
      signal?.addEventListener("abort", onAbort, { once: true });
      if (signal?.aborted === true) {
        onAbort();
        return;
      }
      timeoutHandle = setTimeout(() => {
        execution.finish(
          toolFailure("TIMEOUT", `Script exceeded its ${timeoutMillis} ms deadline.`),
        );
      }, timeoutMillis);
      const workerRequest: WorkerExecutionRequest = {
        inputJson: serializedInput.data.json,
        maxOutputBytes: SANDBOX_LIMITS.maxOutputBytes,
        maxConsoleBytes: SANDBOX_LIMITS.maxConsoleBytes,
        maxConsoleEntries: SANDBOX_LIMITS.maxConsoleEntries,
        maxConsoleEntryBytes: SANDBOX_LIMITS.maxConsoleEntryBytes
      };
      try {
        worker.postMessage(JSON.stringify(workerRequest));
      } catch (error) {
        execution.finish(
          toolFailure("WORKER_FAILED", `Unable to start Worker: ${truncatedMessage(error)}`),
        );
      }
    });
  }

  public close(): void {
    if (this.#closed) return;
    this.#closed = true;
    for (const execution of [...this.#active]) {
      execution.finish(toolFailure("SANDBOX_CLOSED", "The script sandbox was closed."));
    }
  }

  public get activeExecutionCount(): number {
    return this.#active.size;
  }
}

const scriptTool = (
  language: SandboxLanguage,
  sandbox: WorkerSandbox,
): AgentTool<unknown, SandboxExecutionData> => ({
  name: language === "javascript" ? "execute_js" : "execute_ts",
  description:
    `Run a pure ${language === "javascript" ? "JavaScript" : "TypeScript"} async function body in an isolated Worker. ` +
    "Only JSON input, bounded console output, and a JSON return value are available; network, DOM, native bridge, imports, and child workers are disabled.",
  risk: "read",
  executionMode: "parallel",
  inputSchema: scriptToolSchema,
  execute: async (input, context) => {
    const parsed = parseToolInput(input, language);
    if (!parsed.success) return parsed;
    return sandbox.execute(parsed.data, context.signal);
  }
});

export const createScriptTools = (
  sandbox: WorkerSandbox = new WorkerSandbox(),
): readonly AgentTool<unknown, SandboxExecutionData>[] => [
  scriptTool("javascript", sandbox),
  scriptTool("typescript", sandbox)
];
