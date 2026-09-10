# Data Consumption Metrics

EDC data-plane extension that records, per contract, the HTTP status and response size of every request served through the public data-plane API (`DATA_CONTEXT` and `ApiContext.PUBLIC`).

## Table of Contents

- [Overview](#overview)
- [Why `DataPlanePublicApiV2Controller` Uses `StreamingOutput`](#why-dataplanepublicapiv2controller-uses-streamingoutput)
- [How It Works](#how-it-works)
- [How Jersey Actually Calls the Interceptor](#how-jersey-actually-calls-the-interceptor)
- [Testing](#testing)

## Overview

`DataConsumptionMetricsPublisher` is registered as a JAX-RS provider (`BillingConsumptionMetricsExtension`) on the data-plane's public resource contexts. For every response it builds a `DataConsumptionRecord` (contract id, status code, response size, trace context, participant id, timestamp) and hands it to `TelemetryRecordStore`.

## Why `DataPlanePublicApiV2Controller` Uses `StreamingOutput`

The public data-plane transfers data from an external source (SFTP, HTTP, etc.) to the consumer; the size is often unknown ahead of time and can be large. `DataPlanePublicApiV2Controller` returns `Response.ok(streamingOutput)` where `streamingOutput` writes directly to the outgoing HTTP `OutputStream` as `AsyncStreamingDataSink` produces chunks from `PipelineService.transfer` — no full-body buffering on the data-plane.

A `byte[]`/`String` entity would require materializing the entire body before returning: OOM risk on large files, and no bytes reach the client until the whole transfer from the source finishes. `StreamingOutput` combined with `@Suspended AsyncResponse` is Jersey's standard async pattern here: the request thread is freed early, and the actual write happens later, whenever the source has data ready.

This is also why measuring response size correctly matters: at the point `filter()` runs, the entity is only this write callback — no bytes exist yet (see [How It Works](#how-it-works)).

## How It Works

`DataConsumptionMetricsPublisher` implements both `ContainerResponseFilter` and `jakarta.ws.rs.ext.WriterInterceptor`.

`filter()` runs first, before the response body is serialized. It validates `Contract-Id` (`InvalidRequestException` if missing) and either:
- publishes immediately with size `0` for bodyless responses (`entity == null`);
- otherwise stashes `contractId` / `traceContext` / `status` as request properties for `aroundWriteTo` to read — `WriterInterceptorContext` and `ContainerRequestContext` share the same underlying property store for a given request.

`aroundWriteTo()` runs at the actual serialization boundary, after the `MessageBodyWriter` for the entity's type has been resolved but before it writes anything — regardless of whether that writer is `StreamingOutputProvider`, a `byte[]`/`String` provider, or a Jackson `MessageBodyWriter` for a POJO:

```java
@Override
public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
    var countingOutput = new CountingOutputStream(context.getOutputStream());
    context.setOutputStream(countingOutput);
    try {
        context.proceed();
    } finally {
        publish(contractId, countingOutput.getCount(), status, traceContext);
    }
}
```

`CountingOutputStream` counts the exact bytes written to the wire, with no type-specific branching — one mechanism, correct for every entity type Jersey can write.

## How Jersey Actually Calls the Interceptor

This project runs Jersey 3.1.10 (`gradle/libs.versions.toml`). Confirmed by disassembling `jersey-common-3.1.10.jar`:

- `org.glassfish.jersey.message.internal.MessageBodyFactory#writeTo` instantiates `WriterInterceptorExecutor` with the resolved `MessageBodyWriter`, the raw `OutputStream`, and the ordered list of `WriterInterceptor`s, then calls `proceed()` on it.
- `WriterInterceptorExecutor` **is** the `WriterInterceptorContext` passed to each interceptor's `aroundWriteTo`. Each `proceed()` call advances to the next interceptor; the last one invokes the resolved `MessageBodyWriter.writeTo(entity, ..., outputStream)`.
- For a `StreamingOutput` entity (the type used by `DataPlanePublicApiV2Controller` for real transfers), that `MessageBodyWriter` (Jersey's built-in `StreamingOutputProvider`) does nothing more than `entity.write(outputStream)` — i.e. it runs the resource's lambda directly against whatever `OutputStream` the last interceptor installed via `context.setOutputStream(...)`.

So wrapping the stream in `aroundWriteTo` is the mechanism Jersey itself uses to let interceptors observe or transform the exact bytes about to be written, before any provider touches them — not a workaround.

## Testing

`DataConsumptionMetricsPublisherTest` exercises `filter()` and `aroundWriteTo()` independently, without a running Jersey server:

- `filter()` tests stub `ContainerRequestContext`/`ContainerResponseContext` directly and assert what gets published immediately (`entity == null`) versus deferred (`setProperty` calls, no `save()` yet).
- `aroundWriteTo()` tests mock a bare `WriterInterceptorContext`: `getProperty(...)` returns the values `filter()` would have stored, `getEntity()` returns a real `StreamingOutput`, and `proceed()` is stubbed to call `entity.write(...)` against whatever `OutputStream` was captured from `setOutputStream(...)` — reproducing exactly what `WriterInterceptorExecutor` does internally, so the recorded `responseSize` is checked against genuinely different, in some cases randomized, payload sizes.
