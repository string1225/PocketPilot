"use strict";
(() => {
  var __typeError = (msg) => {
    throw TypeError(msg);
  };
  var __accessCheck = (obj, member, msg) => member.has(obj) || __typeError("Cannot " + msg);
  var __privateGet = (obj, member, getter) => (__accessCheck(obj, member, "read from private field"), getter ? getter.call(obj) : member.get(obj));
  var __privateAdd = (obj, member, value) => member.has(obj) ? __typeError("Cannot add the same private member more than once") : member instanceof WeakSet ? member.add(obj) : member.set(obj, value);
  var __privateSet = (obj, member, value, setter) => (__accessCheck(obj, member, "write to private field"), setter ? setter.call(obj, value) : member.set(obj, value), value);
  var __privateMethod = (obj, member, method) => (__accessCheck(obj, member, "access private method"), method);

  // ../tool-runtime/dist/permissions.js
  var __classPrivateFieldSet = function(receiver, state, value, kind, f) {
    if (kind === "m") throw new TypeError("Private method is not writable");
    if (kind === "a" && !f) throw new TypeError("Private accessor was defined without a setter");
    if (typeof state === "function" ? receiver !== state || !f : !state.has(receiver)) throw new TypeError("Cannot write private member to an object whose class did not declare it");
    return kind === "a" ? f.call(receiver, value) : f ? f.value = value : state.set(receiver, value), value;
  };
  var __classPrivateFieldGet = function(receiver, state, kind, f) {
    if (kind === "a" && !f) throw new TypeError("Private accessor was defined without a getter");
    if (typeof state === "function" ? receiver !== state || !f : !state.has(receiver)) throw new TypeError("Cannot read private member from an object whose class did not declare it");
    return kind === "m" ? f : kind === "a" ? f.call(receiver) : f ? f.value : state.get(receiver);
  };
  var _RiskPermissionPolicy_decisions;
  var defaultDecisionByRisk = {
    read: { outcome: "allow" },
    write: { outcome: "allow" },
    network: {
      outcome: "require_approval",
      reason: "Network tools require explicit approval."
    },
    remote: {
      outcome: "require_approval",
      reason: "Remote tools require explicit approval."
    }
  };
  var RiskPermissionPolicy = class {
    constructor(options = {}) {
      var _a, _b, _c, _d;
      _RiskPermissionPolicy_decisions.set(this, void 0);
      __classPrivateFieldSet(this, _RiskPermissionPolicy_decisions, {
        read: (_a = options.read) != null ? _a : defaultDecisionByRisk.read,
        write: (_b = options.write) != null ? _b : defaultDecisionByRisk.write,
        network: (_c = options.network) != null ? _c : defaultDecisionByRisk.network,
        remote: (_d = options.remote) != null ? _d : defaultDecisionByRisk.remote
      }, "f");
    }
    evaluate(request) {
      return __classPrivateFieldGet(this, _RiskPermissionPolicy_decisions, "f")[request.tool.risk];
    }
  };
  _RiskPermissionPolicy_decisions = /* @__PURE__ */ new WeakMap();
  var AllowAllPermissionPolicy = class {
    evaluate() {
      return { outcome: "allow" };
    }
  };

  // ../tool-runtime/dist/types.js
  var toolFailure = (code, message, options = {}) => ({
    success: false,
    error: {
      code,
      message,
      ...options.retryable === void 0 ? {} : { retryable: options.retryable },
      ...options.details === void 0 ? {} : { details: options.details }
    }
  });

  // ../tool-runtime/dist/registry.js
  var __classPrivateFieldSet2 = function(receiver, state, value, kind, f) {
    if (kind === "m") throw new TypeError("Private method is not writable");
    if (kind === "a" && !f) throw new TypeError("Private accessor was defined without a setter");
    if (typeof state === "function" ? receiver !== state || !f : !state.has(receiver)) throw new TypeError("Cannot write private member to an object whose class did not declare it");
    return kind === "a" ? f.call(receiver, value) : f ? f.value = value : state.set(receiver, value), value;
  };
  var __classPrivateFieldGet2 = function(receiver, state, kind, f) {
    if (kind === "a" && !f) throw new TypeError("Private accessor was defined without a getter");
    if (typeof state === "function" ? receiver !== state || !f : !state.has(receiver)) throw new TypeError("Cannot read private member from an object whose class did not declare it");
    return kind === "m" ? f : kind === "a" ? f.call(receiver) : f ? f.value : state.get(receiver);
  };
  var _ToolRegistry_instances;
  var _ToolRegistry_tools;
  var _ToolRegistry_permissionPolicy;
  var _ToolRegistry_onEvent;
  var _ToolRegistry_emit;
  var isAbortError = (error) => error instanceof DOMException ? error.name === "AbortError" : typeof error === "object" && error !== null && "name" in error && error.name === "AbortError";
  var errorMessage = (error) => error instanceof Error ? error.message : String(error);
  var ToolRegistry = class {
    constructor(options = {}) {
      var _a;
      _ToolRegistry_instances.add(this);
      _ToolRegistry_tools.set(this, /* @__PURE__ */ new Map());
      _ToolRegistry_permissionPolicy.set(this, void 0);
      _ToolRegistry_onEvent.set(this, void 0);
      __classPrivateFieldSet2(this, _ToolRegistry_permissionPolicy, (_a = options.permissionPolicy) != null ? _a : new RiskPermissionPolicy(), "f");
      __classPrivateFieldSet2(this, _ToolRegistry_onEvent, options.onEvent, "f");
    }
    register(tool) {
      const name = tool.name.trim();
      if (name.length === 0) {
        throw new Error("Tool name must not be empty.");
      }
      if (name !== tool.name) {
        throw new Error("Tool name must already be normalized: ".concat(tool.name));
      }
      if (__classPrivateFieldGet2(this, _ToolRegistry_tools, "f").has(name)) {
        throw new Error("Tool is already registered: ".concat(name));
      }
      __classPrivateFieldGet2(this, _ToolRegistry_tools, "f").set(name, tool);
      return this;
    }
    registerAll(tools) {
      for (const tool of tools) {
        this.register(tool);
      }
      return this;
    }
    has(name) {
      return __classPrivateFieldGet2(this, _ToolRegistry_tools, "f").has(name);
    }
    list() {
      return [...__classPrivateFieldGet2(this, _ToolRegistry_tools, "f").values()].map((tool) => ({
        name: tool.name,
        description: tool.description,
        inputSchema: tool.inputSchema,
        risk: tool.risk
      }));
    }
    async execute(name, input, context) {
      if (context.signal.aborted) {
        return toolFailure("CANCELLED", "Tool execution was cancelled.");
      }
      const tool = __classPrivateFieldGet2(this, _ToolRegistry_tools, "f").get(name);
      if (tool === void 0) {
        return toolFailure("UNKNOWN_TOOL", "Unknown tool: ".concat(name));
      }
      let decision;
      try {
        decision = await __classPrivateFieldGet2(this, _ToolRegistry_permissionPolicy, "f").evaluate({ tool, input, context });
      } catch (error) {
        return toolFailure("PERMISSION_POLICY_FAILED", "Permission policy failed: ".concat(errorMessage(error)));
      }
      if (decision.outcome === "deny") {
        return toolFailure("PERMISSION_DENIED", decision.reason);
      }
      if (decision.outcome === "require_approval") {
        return toolFailure("APPROVAL_REQUIRED", decision.reason);
      }
      __classPrivateFieldGet2(this, _ToolRegistry_instances, "m", _ToolRegistry_emit).call(this, { type: "tool.started", name, input, context });
      let result;
      try {
        result = await tool.execute(input, context);
        if (context.signal.aborted) {
          result = toolFailure("CANCELLED", "Tool execution was cancelled.");
        }
      } catch (error) {
        result = isAbortError(error) ? toolFailure("CANCELLED", "Tool execution was cancelled.") : toolFailure("TOOL_EXECUTION_FAILED", errorMessage(error));
      }
      __classPrivateFieldGet2(this, _ToolRegistry_instances, "m", _ToolRegistry_emit).call(this, { type: "tool.finished", name, result, context });
      return result;
    }
  };
  _ToolRegistry_tools = /* @__PURE__ */ new WeakMap(), _ToolRegistry_permissionPolicy = /* @__PURE__ */ new WeakMap(), _ToolRegistry_onEvent = /* @__PURE__ */ new WeakMap(), _ToolRegistry_instances = /* @__PURE__ */ new WeakSet(), _ToolRegistry_emit = function _ToolRegistry_emit2(event) {
    var _a;
    try {
      (_a = __classPrivateFieldGet2(this, _ToolRegistry_onEvent, "f")) == null ? void 0 : _a.call(this, event);
    } catch (e) {
    }
  };

  // ../agent-core/dist/runner.js
  var __classPrivateFieldSet3 = function(receiver, state, value, kind, f) {
    if (kind === "m") throw new TypeError("Private method is not writable");
    if (kind === "a" && !f) throw new TypeError("Private accessor was defined without a setter");
    if (typeof state === "function" ? receiver !== state || !f : !state.has(receiver)) throw new TypeError("Cannot write private member to an object whose class did not declare it");
    return kind === "a" ? f.call(receiver, value) : f ? f.value = value : state.set(receiver, value), value;
  };
  var __classPrivateFieldGet3 = function(receiver, state, kind, f) {
    if (kind === "a" && !f) throw new TypeError("Private accessor was defined without a getter");
    if (typeof state === "function" ? receiver !== state || !f : !state.has(receiver)) throw new TypeError("Cannot read private member from an object whose class did not declare it");
    return kind === "m" ? f : kind === "a" ? f.call(receiver) : f ? f.value : state.get(receiver);
  };
  var _DefaultAgentRunner_provider;
  var _DefaultAgentRunner_tools;
  var _DefaultAgentRunner_maxSteps;
  var _DefaultAgentRunner_systemPrompt;
  var _DefaultAgentRunner_now;
  var _DefaultAgentRunner_onEvent;
  var isAbortError2 = (error) => error instanceof DOMException ? error.name === "AbortError" : typeof error === "object" && error !== null && "name" in error && error.name === "AbortError";
  var errorMessage2 = (error) => error instanceof Error ? error.message : String(error);
  var toToolContent = (result) => {
    try {
      return JSON.stringify(result);
    } catch (e) {
      return result.success ? "Tool succeeded with a non-serializable result." : "".concat(result.error.code, ": ").concat(result.error.message);
    }
  };
  var DefaultAgentRunner = class {
    constructor(options) {
      var _a, _b;
      _DefaultAgentRunner_provider.set(this, void 0);
      _DefaultAgentRunner_tools.set(this, void 0);
      _DefaultAgentRunner_maxSteps.set(this, void 0);
      _DefaultAgentRunner_systemPrompt.set(this, void 0);
      _DefaultAgentRunner_now.set(this, void 0);
      _DefaultAgentRunner_onEvent.set(this, void 0);
      const maxSteps = (_a = options.maxSteps) != null ? _a : 8;
      if (!Number.isSafeInteger(maxSteps) || maxSteps < 1) {
        throw new Error("maxSteps must be a positive safe integer.");
      }
      __classPrivateFieldSet3(this, _DefaultAgentRunner_provider, options.provider, "f");
      __classPrivateFieldSet3(this, _DefaultAgentRunner_tools, options.tools, "f");
      __classPrivateFieldSet3(this, _DefaultAgentRunner_maxSteps, maxSteps, "f");
      __classPrivateFieldSet3(this, _DefaultAgentRunner_systemPrompt, options.systemPrompt, "f");
      __classPrivateFieldSet3(this, _DefaultAgentRunner_now, (_b = options.now) != null ? _b : Date.now, "f");
      __classPrivateFieldSet3(this, _DefaultAgentRunner_onEvent, options.onEvent, "f");
    }
    async run(input) {
      var _a, _b, _c, _d;
      if (input.runId.trim().length === 0 || input.projectId.trim().length === 0) {
        throw new Error("runId and projectId must not be empty.");
      }
      if (input.task.trim().length === 0) {
        throw new Error("task must not be empty.");
      }
      const signal = (_a = input.signal) != null ? _a : new AbortController().signal;
      const messages = [
        ...__classPrivateFieldGet3(this, _DefaultAgentRunner_systemPrompt, "f") === void 0 ? [] : [{ role: "system", content: __classPrivateFieldGet3(this, _DefaultAgentRunner_systemPrompt, "f") }],
        ...(_b = input.messages) != null ? _b : [],
        { role: "user", content: input.task }
      ];
      const events = [];
      let sequence = 0;
      const emit = (event) => {
        var _a2;
        const completeEvent = {
          ...event,
          sequence: ++sequence,
          at: __classPrivateFieldGet3(this, _DefaultAgentRunner_now, "f").call(this),
          runId: input.runId,
          projectId: input.projectId
        };
        events.push(completeEvent);
        try {
          (_a2 = __classPrivateFieldGet3(this, _DefaultAgentRunner_onEvent, "f")) == null ? void 0 : _a2.call(this, completeEvent);
        } catch (e) {
        }
      };
      const base = (steps) => ({
        runId: input.runId,
        projectId: input.projectId,
        messages,
        events,
        steps
      });
      const cancelled = (steps) => {
        emit({ type: "run.cancelled", steps });
        return { ...base(steps), status: "cancelled" };
      };
      const failed = (steps, error) => {
        emit({ type: "run.failed", error, steps });
        return { ...base(steps), status: "failed", error };
      };
      emit({ type: "run.started", task: input.task });
      if (signal.aborted) {
        return cancelled(0);
      }
      const seenCallIds = /* @__PURE__ */ new Set();
      for (let step = 1; step <= __classPrivateFieldGet3(this, _DefaultAgentRunner_maxSteps, "f"); step += 1) {
        if (signal.aborted) {
          return cancelled(step - 1);
        }
        let response;
        try {
          response = await __classPrivateFieldGet3(this, _DefaultAgentRunner_provider, "f").complete({
            runId: input.runId,
            projectId: input.projectId,
            step,
            messages: [...messages],
            tools: __classPrivateFieldGet3(this, _DefaultAgentRunner_tools, "f").list(),
            signal
          });
        } catch (error) {
          if (signal.aborted || isAbortError2(error)) {
            return cancelled(step);
          }
          return failed(step, {
            code: "PROVIDER_FAILED",
            message: "".concat(__classPrivateFieldGet3(this, _DefaultAgentRunner_provider, "f").name, ": ").concat(errorMessage2(error)),
            retryable: true
          });
        }
        if (signal.aborted) {
          return cancelled(step);
        }
        const toolCalls = (_c = response.toolCalls) != null ? _c : [];
        const content = (_d = response.content) != null ? _d : "";
        if (content.length > 0) {
          emit({ type: "assistant.message", content });
        }
        if (toolCalls.length === 0) {
          if (response.content === void 0) {
            return failed(step, {
              code: "INVALID_PROVIDER_RESPONSE",
              message: "Provider returned neither content nor tool calls."
            });
          }
          messages.push({ role: "assistant", content });
          emit({ type: "run.completed", output: content, steps: step });
          return { ...base(step), status: "completed", output: content };
        }
        messages.push({
          role: "assistant",
          content,
          toolCalls: [...toolCalls]
        });
        for (const call of toolCalls) {
          if (seenCallIds.has(call.id)) {
            return failed(step, {
              code: "DUPLICATE_TOOL_CALL_ID",
              message: "Provider repeated tool call id: ".concat(call.id)
            });
          }
          seenCallIds.add(call.id);
          if (signal.aborted) {
            return cancelled(step);
          }
          emit({ type: "tool.started", call });
          const result = await __classPrivateFieldGet3(this, _DefaultAgentRunner_tools, "f").execute(call.name, call.arguments, {
            runId: input.runId,
            projectId: input.projectId,
            callId: call.id,
            signal
          });
          emit({ type: "tool.finished", call, result });
          messages.push({
            role: "tool",
            content: toToolContent(result),
            toolCallId: call.id,
            name: call.name,
            result
          });
          if (!result.success && result.error.code === "CANCELLED") {
            return cancelled(step);
          }
          if (!result.success && result.error.code === "APPROVAL_REQUIRED") {
            emit({
              type: "run.waiting_for_approval",
              call,
              reason: result.error.message
            });
            return {
              ...base(step),
              status: "waiting_for_approval",
              pendingCall: call,
              error: result.error
            };
          }
          if (!result.success && result.error.code === "PERMISSION_DENIED") {
            return failed(step, result.error);
          }
        }
      }
      const maxStepsResult = toolFailure("MAX_STEPS_EXCEEDED", "Agent exceeded the maximum of ".concat(__classPrivateFieldGet3(this, _DefaultAgentRunner_maxSteps, "f"), " steps."));
      return failed(__classPrivateFieldGet3(this, _DefaultAgentRunner_maxSteps, "f"), maxStepsResult.error);
    }
  };
  _DefaultAgentRunner_provider = /* @__PURE__ */ new WeakMap(), _DefaultAgentRunner_tools = /* @__PURE__ */ new WeakMap(), _DefaultAgentRunner_maxSteps = /* @__PURE__ */ new WeakMap(), _DefaultAgentRunner_systemPrompt = /* @__PURE__ */ new WeakMap(), _DefaultAgentRunner_now = /* @__PURE__ */ new WeakMap(), _DefaultAgentRunner_onEvent = /* @__PURE__ */ new WeakMap();

  // ../providers/dist/offline.js
  var help = [
    "Offline demo commands:",
    "/list [path]",
    "/create path | content",
    "/read path",
    "/replace path | old text | new text",
    "/delete path"
  ].join("\n");
  var lastMessage = (messages) => messages.length === 0 ? void 0 : messages[messages.length - 1];
  var toolCall = (request, name, args) => ({
    content: "Running ".concat(name, "."),
    toolCalls: [
      {
        id: "".concat(request.runId, ":").concat(request.step, ":offline"),
        name,
        arguments: args
      }
    ]
  });
  var parsePiped = (body) => body.split("|").map((part) => part.trim());
  var commandResponse = (request, task) => {
    const firstSpace = task.indexOf(" ");
    const command = (firstSpace === -1 ? task : task.slice(0, firstSpace)).toLowerCase();
    const body = firstSpace === -1 ? "" : task.slice(firstSpace + 1).trim();
    switch (command) {
      case "/list":
        return toolCall(request, "workspace.list", { path: body });
      case "/read":
        return body.length === 0 ? { content: "A file path is required.\n".concat(help) } : toolCall(request, "workspace.read", { path: body });
      case "/create": {
        const [path, ...contentParts] = parsePiped(body);
        if (path === void 0 || path.length === 0 || contentParts.length === 0) {
          return { content: "Use /create path | content.\n".concat(help) };
        }
        return toolCall(request, "workspace.create", {
          path,
          content: contentParts.join("|").trim()
        });
      }
      case "/replace": {
        const [path, oldText, ...newTextParts] = parsePiped(body);
        if (path === void 0 || path.length === 0 || oldText === void 0 || oldText.length === 0 || newTextParts.length === 0) {
          return { content: "Use /replace path | old text | new text.\n".concat(help) };
        }
        return toolCall(request, "workspace.patch", {
          path,
          oldText,
          newText: newTextParts.join("|").trim(),
          expectedOccurrences: 1
        });
      }
      case "/delete":
        return body.length === 0 ? { content: "A file path is required.\n".concat(help) } : toolCall(request, "workspace.delete", { path: body });
      default:
        return { content: help };
    }
  };
  var summarizeToolResult = (message) => {
    if (message.result.success) {
      let data;
      try {
        data = JSON.stringify(message.result.data, null, 2);
      } catch (e) {
        data = "(non-serializable result)";
      }
      return "".concat(message.name, " completed.\n").concat(data);
    }
    return "".concat(message.name, " failed: ").concat(message.result.error.code, ": ").concat(message.result.error.message);
  };
  var OfflineCommandProvider = class {
    constructor() {
      this.name = "offline-command-provider";
    }
    async complete(request) {
      if (request.signal.aborted) {
        throw new DOMException("The operation was aborted.", "AbortError");
      }
      const latest = lastMessage(request.messages);
      if ((latest == null ? void 0 : latest.role) === "tool") {
        return { content: summarizeToolResult(latest) };
      }
      const userMessage = [...request.messages].reverse().find((message) => message.role === "user");
      return userMessage === void 0 ? { content: help } : commandResponse(request, userMessage.content.trim());
    }
  };

  // ../providers/dist/openai-compatible.js
  var __classPrivateFieldSet4 = function(receiver, state, value, kind, f) {
    if (kind === "m") throw new TypeError("Private method is not writable");
    if (kind === "a" && !f) throw new TypeError("Private accessor was defined without a setter");
    if (typeof state === "function" ? receiver !== state || !f : !state.has(receiver)) throw new TypeError("Cannot write private member to an object whose class did not declare it");
    return kind === "a" ? f.call(receiver, value) : f ? f.value = value : state.set(receiver, value), value;
  };
  var __classPrivateFieldGet4 = function(receiver, state, kind, f) {
    if (kind === "a" && !f) throw new TypeError("Private accessor was defined without a getter");
    if (typeof state === "function" ? receiver !== state || !f : !state.has(receiver)) throw new TypeError("Cannot read private member from an object whose class did not declare it");
    return kind === "m" ? f : kind === "a" ? f.call(receiver) : f ? f.value : state.get(receiver);
  };
  var _OpenAICompatibleProvider_config;
  var _OpenAICompatibleProvider_transport;
  var DEFAULT_LLM_MODEL = "glm-5.2";
  var DEFAULT_CHAT_COMPLETIONS_BASE_URL = "https://open.bigmodel.cn/api/coding/paas/v4";
  var DEFAULT_RESPONSES_BASE_URL = "https://open.bigmodel.cn/api/v1";
  var DEFAULT_CHAT_COMPLETIONS_CONFIG = Object.freeze({
    protocol: "chat_completions",
    baseUrl: DEFAULT_CHAT_COMPLETIONS_BASE_URL,
    model: DEFAULT_LLM_MODEL,
    credentialId: ""
  });
  var DEFAULT_RESPONSES_CONFIG = Object.freeze({
    protocol: "responses",
    baseUrl: DEFAULT_RESPONSES_BASE_URL,
    model: DEFAULT_LLM_MODEL,
    credentialId: ""
  });
  var normalizedEndpoint = (rawBaseUrl) => {
    if (rawBaseUrl.length > 2048) {
      throw new Error("LLM endpoint is too long.");
    }
    const baseUrl = new URL(rawBaseUrl);
    if (baseUrl.protocol !== "https:") {
      throw new Error("LLM endpoint must use HTTPS.");
    }
    if (baseUrl.port === "0") {
      throw new Error("LLM endpoint port is invalid.");
    }
    if (baseUrl.username.length > 0 || baseUrl.password.length > 0 || baseUrl.search.length > 0 || baseUrl.hash.length > 0) {
      throw new Error("LLM endpoint must not embed credentials, query parameters, or fragments.");
    }
    return baseUrl.toString().replace(/\/$/u, "");
  };
  var resolveOpenAICompatibleConfig = (config) => {
    var _a, _b, _c;
    const protocol = (_a = config.protocol) != null ? _a : "chat_completions";
    const defaultBaseUrl = protocol === "responses" ? DEFAULT_RESPONSES_BASE_URL : DEFAULT_CHAT_COMPLETIONS_BASE_URL;
    const rawBaseUrl = ((_b = config.baseUrl) == null ? void 0 : _b.trim()) || defaultBaseUrl;
    const model = ((_c = config.model) == null ? void 0 : _c.trim()) || DEFAULT_LLM_MODEL;
    const credentialId = config.credentialId.trim();
    if (model.length > 128) {
      throw new Error("LLM model identifier is too long.");
    }
    if (credentialId.length === 0 || credentialId.length > 256) {
      throw new Error("credentialId must contain between 1 and 256 characters.");
    }
    return {
      protocol,
      baseUrl: normalizedEndpoint(rawBaseUrl),
      model,
      credentialId
    };
  };
  var OpenAICompatibleProvider = class {
    constructor(config, transport) {
      _OpenAICompatibleProvider_config.set(this, void 0);
      _OpenAICompatibleProvider_transport.set(this, void 0);
      __classPrivateFieldSet4(this, _OpenAICompatibleProvider_config, resolveOpenAICompatibleConfig(config), "f");
      __classPrivateFieldSet4(this, _OpenAICompatibleProvider_transport, transport, "f");
      this.name = "openai-compatible:".concat(__classPrivateFieldGet4(this, _OpenAICompatibleProvider_config, "f").protocol, ":").concat(__classPrivateFieldGet4(this, _OpenAICompatibleProvider_config, "f").model);
    }
    complete(request) {
      return __classPrivateFieldGet4(this, _OpenAICompatibleProvider_transport, "f").complete(__classPrivateFieldGet4(this, _OpenAICompatibleProvider_config, "f"), request);
    }
  };
  _OpenAICompatibleProvider_config = /* @__PURE__ */ new WeakMap(), _OpenAICompatibleProvider_transport = /* @__PURE__ */ new WeakMap();

  // ../providers/dist/scripted.js
  var _ScriptedProvider_steps;
  _ScriptedProvider_steps = /* @__PURE__ */ new WeakMap();

  // src/llm-transport.ts
  var NativeLlmTransportError = class extends Error {
    constructor(code, message, retryable = false) {
      super(message);
      this.name = "NativeLlmTransportError";
      this.code = code;
      this.retryable = retryable;
    }
  };
  var asRecord = (value) => typeof value === "object" && value !== null && !Array.isArray(value) ? value : void 0;
  var serializeMessage = (message) => {
    switch (message.role) {
      case "assistant":
        return {
          role: message.role,
          content: message.content,
          ...message.toolCalls === void 0 ? {} : { toolCalls: message.toolCalls.map((call) => ({ ...call })) }
        };
      case "tool":
        return {
          role: message.role,
          content: message.content,
          toolCallId: message.toolCallId,
          name: message.name
        };
      case "system":
      case "user":
        return { role: message.role, content: message.content };
    }
  };
  var parseToolCall = (value) => {
    const record = asRecord(value);
    if (record === void 0 || typeof record.id !== "string" || record.id.length === 0 || typeof record.name !== "string" || record.name.length === 0 || !("arguments" in record)) {
      throw new NativeLlmTransportError(
        "LLM_INVALID_RESPONSE",
        "Native LLM response contains an invalid tool call."
      );
    }
    return { id: record.id, name: record.name, arguments: record.arguments };
  };
  var parseProviderResponse = (value) => {
    const record = asRecord(value);
    if (record === void 0) {
      throw new NativeLlmTransportError(
        "LLM_INVALID_RESPONSE",
        "Native LLM response must be an object."
      );
    }
    const content = record.content;
    if (content !== void 0 && typeof content !== "string") {
      throw new NativeLlmTransportError(
        "LLM_INVALID_RESPONSE",
        "Native LLM response content must be a string."
      );
    }
    const rawToolCalls = record.toolCalls;
    if (rawToolCalls !== void 0 && !Array.isArray(rawToolCalls)) {
      throw new NativeLlmTransportError(
        "LLM_INVALID_RESPONSE",
        "Native LLM response toolCalls must be an array."
      );
    }
    const toolCalls = rawToolCalls == null ? void 0 : rawToolCalls.map(parseToolCall);
    if (content === void 0 && toolCalls === void 0) {
      throw new NativeLlmTransportError(
        "LLM_INVALID_RESPONSE",
        "Native LLM response contains neither content nor tool calls."
      );
    }
    return {
      ...content === void 0 ? {} : { content },
      ...toolCalls === void 0 ? {} : { toolCalls }
    };
  };
  var _rpc;
  var NativeRpcLlmTransport = class {
    constructor(rpc) {
      __privateAdd(this, _rpc);
      __privateSet(this, _rpc, rpc);
    }
    async complete(config, request) {
      var _a;
      const payload = {
        protocol: config.protocol,
        baseUrl: config.baseUrl,
        model: config.model,
        credentialId: config.credentialId,
        messages: request.messages.map(serializeMessage),
        tools: request.tools.map((tool) => ({
          name: tool.name,
          description: tool.description,
          inputSchema: tool.inputSchema
        }))
      };
      const result = await __privateGet(this, _rpc).execute("llm.complete", payload, {
        runId: request.runId,
        projectId: request.projectId,
        callId: "llm:".concat(request.runId, ":").concat(request.step),
        signal: request.signal
      });
      if (!result.success) {
        throw new NativeLlmTransportError(
          result.error.code,
          result.error.message,
          (_a = result.error.retryable) != null ? _a : false
        );
      }
      return parseProviderResponse(result.data);
    }
  };
  _rpc = new WeakMap();

  // src/protocol.ts
  var ANDROID_BRIDGE_VERSION = 1;
  var asRecord2 = (value) => typeof value === "object" && value !== null && !Array.isArray(value) ? value : void 0;
  var parseProviderConfig = (value) => {
    if (value === void 0) return void 0;
    const record = asRecord2(value);
    if (record === void 0 || typeof record.type !== "string") {
      throw new Error("provider must be an object with a supported type.");
    }
    const forbiddenSecretField = Object.keys(record).find((key) => {
      const normalized = key.replace(/[_ -]/gu, "").toLowerCase();
      return normalized === "apikey" || normalized === "authorization" || normalized === "bearertoken";
    });
    if (forbiddenSecretField !== void 0) {
      throw new Error("API key material must not be sent to the TypeScript runtime.");
    }
    if (record.type === "offline") return { type: "offline" };
    if (record.type !== "openai_compatible") {
      throw new Error("Unsupported provider type: ".concat(record.type));
    }
    const allowedKeys = /* @__PURE__ */ new Set(["type", "protocol", "baseUrl", "model", "credentialId"]);
    if (Object.keys(record).some((key) => !allowedKeys.has(key))) {
      throw new Error("provider contains an unsupported field.");
    }
    const protocol = record.protocol;
    if (protocol !== void 0 && protocol !== "chat_completions" && protocol !== "responses") {
      throw new Error("provider.protocol must be chat_completions or responses.");
    }
    const credentialId = record.credentialId;
    if (typeof credentialId !== "string" || credentialId.trim().length === 0) {
      throw new Error("provider.credentialId must be a non-empty string.");
    }
    const baseUrl = record.baseUrl;
    const model = record.model;
    if (baseUrl !== void 0 && typeof baseUrl !== "string") {
      throw new Error("provider.baseUrl must be a string.");
    }
    if (model !== void 0 && typeof model !== "string") {
      throw new Error("provider.model must be a string.");
    }
    return {
      type: "openai_compatible",
      credentialId,
      ...protocol === void 0 ? {} : { protocol },
      ...baseUrl === void 0 ? {} : { baseUrl },
      ...model === void 0 ? {} : { model }
    };
  };
  var parseHistory = (value) => {
    if (value === void 0) return void 0;
    if (!Array.isArray(value) || value.length > 100) {
      throw new Error("messages must be an array with at most 100 items.");
    }
    return value.map((item) => {
      const record = asRecord2(item);
      if (record === void 0 || record.role !== "user" && record.role !== "assistant" || typeof record.content !== "string" || record.content.length > 4 * 1048576) {
        throw new Error("Historical messages must contain a user/assistant role and text content.");
      }
      return { role: record.role, content: record.content };
    });
  };
  var parseRuntimeStartRequest = (json) => {
    let parsed;
    try {
      parsed = JSON.parse(json);
    } catch (e) {
      throw new Error("Runtime start request is not valid JSON.");
    }
    const record = asRecord2(parsed);
    if (record === void 0) {
      throw new Error("Runtime start request must be an object.");
    }
    const {
      runId,
      projectId,
      task,
      maxSteps,
      provider,
      systemPrompt,
      messages,
      toolsEnabled
    } = record;
    if (typeof runId !== "string" || runId.trim().length === 0 || typeof projectId !== "string" || projectId.trim().length === 0 || typeof task !== "string" || task.trim().length === 0) {
      throw new Error("runId, projectId, and task must be non-empty strings.");
    }
    if (maxSteps !== void 0 && (typeof maxSteps !== "number" || !Number.isSafeInteger(maxSteps) || maxSteps < 1 || maxSteps > 64)) {
      throw new Error("maxSteps must be an integer between 1 and 64.");
    }
    const parsedProvider = parseProviderConfig(provider);
    if (systemPrompt !== void 0 && (typeof systemPrompt !== "string" || systemPrompt.length > 16384)) {
      throw new Error("systemPrompt must be a string no longer than 16384 characters.");
    }
    if (toolsEnabled !== void 0 && typeof toolsEnabled !== "boolean") {
      throw new Error("toolsEnabled must be a boolean.");
    }
    const parsedMessages = parseHistory(messages);
    return {
      runId,
      projectId,
      task,
      ...maxSteps === void 0 ? {} : { maxSteps },
      ...parsedProvider === void 0 ? {} : { provider: parsedProvider },
      ...systemPrompt === void 0 ? {} : { systemPrompt },
      ...parsedMessages === void 0 ? {} : { messages: parsedMessages },
      ...toolsEnabled === void 0 ? {} : { toolsEnabled }
    };
  };
  var parseNativeEnvelope = (json) => {
    let parsed;
    try {
      parsed = JSON.parse(json);
    } catch (e) {
      throw new Error("Native bridge envelope is not valid JSON.");
    }
    const record = asRecord2(parsed);
    if (record === void 0 || record.version !== ANDROID_BRIDGE_VERSION || typeof record.id !== "string" || typeof record.runId !== "string" || typeof record.projectId !== "string" || record.type !== "tool.result" && record.type !== "tool.error" || !("payload" in record)) {
      throw new Error("Native bridge envelope has an invalid shape.");
    }
    return record;
  };

  // src/native-rpc.ts
  var nextEnvelopeId = 0;
  var defaultCreateId = (context) => {
    nextEnvelopeId += 1;
    return "".concat(context.callId, ":").concat(nextEnvelopeId);
  };
  var isToolResult = (value) => {
    if (typeof value !== "object" || value === null || !("success" in value)) {
      return false;
    }
    const success = value.success;
    if (success === true) {
      return "data" in value;
    }
    if (success !== false || !("error" in value)) {
      return false;
    }
    const error = value.error;
    return typeof error === "object" && error !== null && "code" in error && typeof error.code === "string" && "message" in error && typeof error.message === "string";
  };
  var nativeErrorResult = (payload) => {
    if (isToolResult(payload) && !payload.success) {
      return payload;
    }
    if (typeof payload === "object" && payload !== null) {
      const record = payload;
      if (typeof record.code === "string" && typeof record.message === "string") {
        return toolFailure(record.code, record.message, {
          ...typeof record.retryable === "boolean" ? { retryable: record.retryable } : {}
        });
      }
    }
    return toolFailure("NATIVE_TOOL_FAILED", "Native tool execution failed.");
  };
  var _postMessage, _createId, _pending, _NativeRpcClient_instances, settle_fn;
  var NativeRpcClient = class {
    constructor(options) {
      __privateAdd(this, _NativeRpcClient_instances);
      __privateAdd(this, _postMessage);
      __privateAdd(this, _createId);
      __privateAdd(this, _pending, /* @__PURE__ */ new Map());
      var _a;
      __privateSet(this, _postMessage, options.postMessage);
      __privateSet(this, _createId, (_a = options.createId) != null ? _a : defaultCreateId);
    }
    execute(name, input, context) {
      if (context.signal.aborted) {
        return Promise.resolve(toolFailure("CANCELLED", "Tool execution was cancelled."));
      }
      const id = __privateGet(this, _createId).call(this, context);
      if (__privateGet(this, _pending).has(id)) {
        return Promise.resolve(
          toolFailure("BRIDGE_DUPLICATE_ID", "Bridge envelope id already exists: ".concat(id))
        );
      }
      const envelope = {
        version: ANDROID_BRIDGE_VERSION,
        id,
        type: "tool.request",
        runId: context.runId,
        projectId: context.projectId,
        payload: { name, arguments: input === void 0 ? null : input }
      };
      return new Promise((resolve) => {
        const finish = (result) => {
          const pending = __privateGet(this, _pending).get(id);
          if (pending === void 0) {
            return;
          }
          __privateGet(this, _pending).delete(id);
          pending.signal.removeEventListener("abort", pending.onAbort);
          resolve(result);
        };
        const onAbort = () => {
          finish(toolFailure("CANCELLED", "Tool execution was cancelled."));
        };
        __privateGet(this, _pending).set(id, {
          runId: context.runId,
          projectId: context.projectId,
          signal: context.signal,
          onAbort,
          resolve
        });
        context.signal.addEventListener("abort", onAbort, { once: true });
        if (context.signal.aborted) {
          onAbort();
          return;
        }
        let serialized;
        try {
          serialized = JSON.stringify(envelope);
        } catch (e) {
          finish(
            toolFailure("BRIDGE_SERIALIZATION_FAILED", "Tool arguments are not serializable.")
          );
          return;
        }
        try {
          __privateGet(this, _postMessage).call(this, serialized);
        } catch (error) {
          finish(
            toolFailure(
              "BRIDGE_UNAVAILABLE",
              error instanceof Error ? error.message : String(error),
              { retryable: true }
            )
          );
        }
      });
    }
    receive(envelopeJson) {
      let envelope;
      try {
        envelope = parseNativeEnvelope(envelopeJson);
      } catch (e) {
        return;
      }
      const pending = __privateGet(this, _pending).get(envelope.id);
      if (pending === void 0) {
        return;
      }
      if (pending.runId !== envelope.runId || pending.projectId !== envelope.projectId) {
        __privateMethod(this, _NativeRpcClient_instances, settle_fn).call(this, envelope.id, toolFailure(
          "BRIDGE_CONTEXT_MISMATCH",
          "Native response does not match the pending run and project."
        ));
        return;
      }
      if (envelope.type === "tool.error") {
        __privateMethod(this, _NativeRpcClient_instances, settle_fn).call(this, envelope.id, nativeErrorResult(envelope.payload));
        return;
      }
      __privateMethod(this, _NativeRpcClient_instances, settle_fn).call(this, envelope.id, isToolResult(envelope.payload) ? envelope.payload : toolFailure(
        "BRIDGE_INVALID_RESULT",
        "Native tool result has an invalid shape."
      ));
    }
    get pendingCount() {
      return __privateGet(this, _pending).size;
    }
  };
  _postMessage = new WeakMap();
  _createId = new WeakMap();
  _pending = new WeakMap();
  _NativeRpcClient_instances = new WeakSet();
  settle_fn = function(id, result) {
    const pending = __privateGet(this, _pending).get(id);
    if (pending === void 0) {
      return;
    }
    __privateGet(this, _pending).delete(id);
    pending.signal.removeEventListener("abort", pending.onAbort);
    pending.resolve(result);
  };
  var objectSchema = (properties, required = []) => ({
    type: "object",
    properties,
    required,
    additionalProperties: false
  });
  var workspacePathSchema = {
    type: "string",
    minLength: 1,
    maxLength: 4096,
    description: "A project-relative workspace path. Absolute paths and traversal are forbidden."
  };
  var optionalWorkspacePathSchema = {
    type: "string",
    maxLength: 4096,
    default: "",
    description: "An optional project-relative directory; use an empty string for the workspace root."
  };
  var workspaceContentSchema = {
    type: "string",
    maxLength: 1048576,
    description: "UTF-8 text content, limited to 1 MiB by the native workspace."
  };
  var gitRemoteSchema = {
    type: "string",
    minLength: 1,
    maxLength: 4096,
    description: "A credential-free HTTPS Git remote URL."
  };
  var gitBranchSchema = {
    type: "string",
    minLength: 1,
    maxLength: 256
  };
  var gitRemoteNameSchema = {
    type: "string",
    minLength: 1,
    maxLength: 128,
    pattern: "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"
  };
  var gitCredentialProperties = {
    useCredential: {
      type: "boolean",
      default: false,
      description: "Use the app's dedicated Git token credential."
    },
    username: {
      type: "string",
      minLength: 1,
      maxLength: 256,
      default: "git",
      description: "HTTPS Git username; used only when useCredential is true."
    }
  };
  var gitTimeoutMillisSchema = {
    type: "integer",
    minimum: 1e3,
    maximum: 36e5,
    multipleOf: 1e3,
    default: 6e4,
    description: "Git network timeout in milliseconds, in whole-second increments."
  };
  var sshTimeoutMillisSchema = {
    type: "integer",
    minimum: 1,
    maximum: 36e5,
    default: 6e4,
    description: "Operation timeout in milliseconds (1 to 3600000)."
  };
  var workspaceSchemas = {
    list: objectSchema({ path: optionalWorkspacePathSchema }),
    read: objectSchema({ path: workspacePathSchema }, ["path"]),
    write: objectSchema(
      { path: workspacePathSchema, content: workspaceContentSchema },
      ["path", "content"]
    ),
    create: objectSchema(
      { path: workspacePathSchema, content: workspaceContentSchema },
      ["path", "content"]
    ),
    delete: objectSchema({ path: workspacePathSchema }, ["path"]),
    move: objectSchema(
      { from: workspacePathSchema, to: workspacePathSchema },
      ["from", "to"]
    ),
    search: objectSchema(
      {
        query: { type: "string", minLength: 1, maxLength: 65536 },
        limit: { type: "integer", minimum: 1, maximum: 500, default: 100 }
      },
      ["query"]
    ),
    patch: objectSchema(
      {
        path: workspacePathSchema,
        oldText: { ...workspaceContentSchema, minLength: 1 },
        newText: workspaceContentSchema,
        expectedOccurrences: {
          type: "integer",
          enum: [1],
          default: 1,
          description: "The native MVP requires exactly one occurrence."
        }
      },
      ["path", "oldText", "newText"]
    )
  };
  var gitSchemas = {
    init: objectSchema({ initialBranch: { ...gitBranchSchema, default: "main" } }),
    clone: objectSchema(
      {
        remoteUrl: gitRemoteSchema,
        branch: gitBranchSchema,
        ...gitCredentialProperties,
        timeoutMillis: gitTimeoutMillisSchema
      },
      ["remoteUrl"]
    ),
    status: objectSchema({}),
    diff: objectSchema({
      maxBytes: {
        type: "integer",
        minimum: 1,
        maximum: 524288,
        default: 524288,
        description: "Maximum UTF-8 bytes returned for each of the staged and unstaged patches."
      }
    }),
    commit: objectSchema(
      {
        message: { type: "string", minLength: 1, maxLength: 65536 },
        authorName: { type: "string", minLength: 1, maxLength: 256 },
        authorEmail: { type: "string", minLength: 3, maxLength: 320 }
      },
      ["message", "authorName", "authorEmail"]
    ),
    pull: objectSchema({
      remote: { ...gitRemoteNameSchema, default: "origin" },
      branch: gitBranchSchema,
      ...gitCredentialProperties,
      timeoutMillis: gitTimeoutMillisSchema
    }),
    push: objectSchema({
      remote: { ...gitRemoteNameSchema, default: "origin" },
      ...gitCredentialProperties,
      timeoutMillis: gitTimeoutMillisSchema
    })
  };
  var sshExecuteSchema = objectSchema(
    {
      server: {
        type: "string",
        minLength: 1,
        maxLength: 512,
        description: "The id or name of a user-configured SSH server."
      },
      command: {
        type: "string",
        minLength: 1,
        maxLength: 4096,
        description: "The command text shown in native approval before execution."
      },
      timeoutMillis: sshTimeoutMillisSchema,
      maxOutputBytes: {
        type: "integer",
        minimum: 1,
        maximum: 524288,
        default: 524288
      }
    },
    ["server", "command"]
  );
  var nativeTool = (rpc, name, description, risk, inputSchema) => ({
    name,
    description,
    risk,
    inputSchema,
    execute: (input, context) => rpc.execute(name, input, context)
  });
  var createNativeWorkspaceTools = (rpc) => [
    nativeTool(rpc, "workspace.list", "List a workspace directory with bounded entries and truncation metadata.", "read", workspaceSchemas.list),
    nativeTool(rpc, "workspace.read", "Read a workspace text file.", "read", workspaceSchemas.read),
    nativeTool(rpc, "workspace.write", "Write an existing workspace text file.", "write", workspaceSchemas.write),
    nativeTool(rpc, "workspace.create", "Create a workspace text file.", "write", workspaceSchemas.create),
    nativeTool(rpc, "workspace.delete", "Delete one workspace file after native approval.", "write", workspaceSchemas.delete),
    nativeTool(rpc, "workspace.move", "Move a workspace path.", "write", workspaceSchemas.move),
    nativeTool(rpc, "workspace.search", "Search workspace text files.", "read", workspaceSchemas.search),
    nativeTool(rpc, "workspace.patch", "Apply one exact-text workspace replacement.", "write", workspaceSchemas.patch),
    nativeTool(rpc, "git.init", "Initialize Git after native approval.", "write", gitSchemas.init),
    nativeTool(rpc, "git.clone", "Clone an HTTPS Git repository after native approval; timeoutMillis is in milliseconds.", "network", gitSchemas.clone),
    nativeTool(rpc, "git.status", "Read bounded Git status entries and truncation metadata for the current project workspace.", "read", gitSchemas.status),
    nativeTool(rpc, "git.diff", "Read separately bounded staged and unstaged Git patches.", "read", gitSchemas.diff),
    nativeTool(rpc, "git.commit", "Stage changes and create a Git commit after native approval.", "write", gitSchemas.commit),
    nativeTool(rpc, "git.pull", "Pull from an HTTPS Git remote after native approval; timeoutMillis is in milliseconds.", "network", gitSchemas.pull),
    nativeTool(rpc, "git.push", "Push to an HTTPS Git remote after native approval and return bounded update metadata; timeoutMillis is in milliseconds.", "network", gitSchemas.push),
    nativeTool(rpc, "ssh.execute", "Execute one command after native approval; timeoutMillis is in milliseconds.", "remote", sshExecuteSchema)
  ];

  // src/runtime.ts
  var fallbackFailure = (request, error) => ({
    runId: request.runId,
    projectId: request.projectId,
    status: "failed",
    steps: 0,
    messages: [{ role: "user", content: request.task }],
    events: [],
    error: {
      code: "RUNTIME_FAILED",
      message: error instanceof Error ? error.message : String(error)
    }
  });
  var _postMessage2, _providerFactory, _rpc2, _runs, _AndroidAgentRuntime_instances, postEvent_fn;
  var AndroidAgentRuntime = class {
    constructor(options) {
      __privateAdd(this, _AndroidAgentRuntime_instances);
      __privateAdd(this, _postMessage2);
      __privateAdd(this, _providerFactory);
      __privateAdd(this, _rpc2);
      __privateAdd(this, _runs, /* @__PURE__ */ new Map());
      var _a;
      __privateSet(this, _postMessage2, options.postMessage);
      __privateSet(this, _rpc2, new NativeRpcClient({ postMessage: options.postMessage }));
      __privateSet(this, _providerFactory, (_a = options.providerFactory) != null ? _a : ((request) => {
        const provider = request.provider;
        if (provider === void 0 || provider.type === "offline") {
          return new OfflineCommandProvider();
        }
        return new OpenAICompatibleProvider(
          {
            credentialId: provider.credentialId,
            ...provider.protocol === void 0 ? {} : { protocol: provider.protocol },
            ...provider.baseUrl === void 0 ? {} : { baseUrl: provider.baseUrl },
            ...provider.model === void 0 ? {} : { model: provider.model }
          },
          new NativeRpcLlmTransport(__privateGet(this, _rpc2))
        );
      }));
    }
    async start(requestJson) {
      var _a;
      const request = parseRuntimeStartRequest(requestJson);
      if (__privateGet(this, _runs).has(request.runId)) {
        throw new Error("Run is already active: ".concat(request.runId));
      }
      const controller = new AbortController();
      const registry = new ToolRegistry({
        permissionPolicy: new AllowAllPermissionPolicy()
      }).registerAll(
        request.toolsEnabled === false ? [] : createNativeWorkspaceTools(__privateGet(this, _rpc2))
      );
      const runner = new DefaultAgentRunner({
        provider: __privateGet(this, _providerFactory).call(this, request),
        tools: registry,
        maxSteps: (_a = request.maxSteps) != null ? _a : 8,
        ...request.systemPrompt === void 0 ? {} : { systemPrompt: request.systemPrompt },
        onEvent: (event) => {
          __privateMethod(this, _AndroidAgentRuntime_instances, postEvent_fn).call(this, event);
        }
      });
      const promise = runner.run({
        runId: request.runId,
        projectId: request.projectId,
        task: request.task,
        ...request.messages === void 0 ? {} : { messages: request.messages },
        signal: controller.signal
      }).catch((error) => {
        const result = fallbackFailure(request, error);
        const failureEvent = {
          type: "run.failed",
          runId: request.runId,
          projectId: request.projectId,
          sequence: 1,
          at: Date.now(),
          steps: 0,
          error: result.status === "failed" ? result.error : {
            code: "RUNTIME_FAILED",
            message: "Runtime failed."
          }
        };
        __privateMethod(this, _AndroidAgentRuntime_instances, postEvent_fn).call(this, failureEvent);
        return result;
      });
      __privateGet(this, _runs).set(request.runId, { controller, promise });
      try {
        const result = await promise;
        return JSON.stringify(result);
      } finally {
        __privateGet(this, _runs).delete(request.runId);
      }
    }
    receive(envelopeJson) {
      __privateGet(this, _rpc2).receive(envelopeJson);
    }
    cancel(runId) {
      const active = __privateGet(this, _runs).get(runId);
      if (active === void 0) {
        return false;
      }
      active.controller.abort();
      return true;
    }
    get activeRunCount() {
      return __privateGet(this, _runs).size;
    }
    get pendingToolCount() {
      return __privateGet(this, _rpc2).pendingCount;
    }
  };
  _postMessage2 = new WeakMap();
  _providerFactory = new WeakMap();
  _rpc2 = new WeakMap();
  _runs = new WeakMap();
  _AndroidAgentRuntime_instances = new WeakSet();
  postEvent_fn = function(event) {
    const envelope = {
      version: ANDROID_BRIDGE_VERSION,
      id: "event:".concat(event.runId, ":").concat(event.sequence),
      type: "event",
      runId: event.runId,
      projectId: event.projectId,
      payload: event
    };
    try {
      __privateGet(this, _postMessage2).call(this, JSON.stringify(envelope));
    } catch (e) {
    }
  };

  // src/abort-controller-fallback.ts
  var resolveRuntimeGlobal = () => {
    if (typeof window !== "undefined") return window;
    if (typeof globalThis !== "undefined") return globalThis;
    throw new Error("PocketPilot runtime global object is unavailable.");
  };
  var _listeners;
  var LightweightAbortSignal = class {
    constructor() {
      this.aborted = false;
      this.reason = void 0;
      __privateAdd(this, _listeners, /* @__PURE__ */ new Map());
    }
    addEventListener(type, listener, options) {
      if (type !== "abort" || listener === null || __privateGet(this, _listeners).has(listener)) {
        return;
      }
      const once = typeof options === "object" && options.once === true;
      __privateGet(this, _listeners).set(listener, once);
    }
    removeEventListener(type, listener) {
      if (type === "abort" && listener !== null) {
        __privateGet(this, _listeners).delete(listener);
      }
    }
    throwIfAborted() {
      if (!this.aborted) return;
      throw this.reason;
    }
    dispatchAbort(reason) {
      if (this.aborted) return;
      this.aborted = true;
      this.reason = reason;
      const event = new Event("abort");
      for (const [listener, once] of [...__privateGet(this, _listeners)]) {
        if (once) __privateGet(this, _listeners).delete(listener);
        try {
          if (typeof listener === "function") {
            listener.call(this, event);
          } else {
            listener.handleEvent(event);
          }
        } catch (e) {
        }
      }
    }
  };
  _listeners = new WeakMap();
  var _mutableSignal;
  var LightweightAbortController = class {
    constructor() {
      __privateAdd(this, _mutableSignal, new LightweightAbortSignal());
      this.signal = __privateGet(this, _mutableSignal);
    }
    abort(reason) {
      const fallbackReason = reason != null ? reason : new DOMException("This operation was aborted", "AbortError");
      __privateGet(this, _mutableSignal).dispatchAbort(fallbackReason);
    }
  };
  _mutableSignal = new WeakMap();
  var installAbortControllerFallback = (target = resolveRuntimeGlobal()) => {
    if (typeof target.AbortController === "function") return false;
    target.AbortController = LightweightAbortController;
    return true;
  };

  // src/install.ts
  var errorMessage3 = (error) => error instanceof Error ? error.message : String(error);
  var parseStartFailureContext = (requestJson) => {
    try {
      const value = JSON.parse(requestJson);
      if (typeof value !== "object" || value === null || Array.isArray(value)) return void 0;
      const record = value;
      if (typeof record.runId !== "string" || record.runId.trim().length === 0 || typeof record.projectId !== "string" || record.projectId.trim().length === 0) {
        return void 0;
      }
      return {
        runId: record.runId,
        projectId: record.projectId,
        task: typeof record.task === "string" ? record.task : ""
      };
    } catch (e) {
      return void 0;
    }
  };
  var installPocketPilotRuntime = (target = resolveRuntimeGlobal()) => {
    var _a;
    installAbortControllerFallback();
    const runtime = new AndroidAgentRuntime({
      postMessage: (envelopeJson) => {
        const bridge = target.PocketPilotNativeBridge;
        if (bridge === void 0 || typeof bridge.postMessage !== "function") {
          throw new Error("PocketPilotNativeBridge.postMessage is unavailable.");
        }
        bridge.postMessage(envelopeJson);
      }
    });
    const api = {
      start: (requestJson) => runtime.start(requestJson).catch((error) => {
        var _a2, _b, _c;
        const context = parseStartFailureContext(requestJson);
        const failure = {
          runId: (_a2 = context == null ? void 0 : context.runId) != null ? _a2 : "",
          projectId: (_b = context == null ? void 0 : context.projectId) != null ? _b : "",
          status: "failed",
          steps: 0,
          messages: (context == null ? void 0 : context.task) === void 0 || context.task.length === 0 ? [] : [{ role: "user", content: context.task }],
          events: [],
          error: {
            code: "RUNTIME_START_FAILED",
            message: errorMessage3(error)
          }
        };
        if (context !== void 0) {
          try {
            (_c = target.PocketPilotNativeBridge) == null ? void 0 : _c.postMessage(
              JSON.stringify({
                version: ANDROID_BRIDGE_VERSION,
                id: "event:".concat(context.runId, ":start-failed"),
                type: "event",
                runId: context.runId,
                projectId: context.projectId,
                payload: {
                  type: "run.failed",
                  runId: context.runId,
                  projectId: context.projectId,
                  sequence: 1,
                  at: Date.now(),
                  steps: 0,
                  error: failure.error
                }
              })
            );
          } catch (e) {
          }
        }
        return JSON.stringify(failure);
      }),
      receive: (envelopeJson) => {
        runtime.receive(envelopeJson);
      },
      cancel: (runId) => runtime.cancel(runId)
    };
    target.PocketPilotRuntime = api;
    try {
      (_a = target.PocketPilotNativeBridge) == null ? void 0 : _a.postMessage(
        JSON.stringify({
          version: ANDROID_BRIDGE_VERSION,
          id: "runtime.ready",
          type: "event",
          runId: "",
          projectId: "",
          payload: {
            type: "runtime.ready",
            runtimeVersion: "0.1.0",
            protocolVersion: ANDROID_BRIDGE_VERSION
          }
        })
      );
    } catch (e) {
    }
    return api;
  };

  // src/global.ts
  installPocketPilotRuntime();
})();
