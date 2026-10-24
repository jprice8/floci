package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.FourWorkerThreadsProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A synchronous Invoke or function URL request waits for its function without holding one of the
 * shared request workers. The profile leaves four workers, and four invocations held at the service
 * boundary would otherwise queue every request that still needs a worker, such as the health check
 * and SSM, behind them. The service is mocked, so the test exercises the REST dispatch and its
 * request scope, not a runtime.
 */
@QuarkusTest
@TestProfile(FourWorkerThreadsProfile.class)
class LambdaSynchronousInvokeDispatchIntegrationTest {

    /** One held invocation per worker, each in its own account and Region. */
    private static final List<Scope> SCOPES = List.of(
            new Scope("111122223333", "us-east-1"),
            new Scope("111122223333", "us-west-2"),
            new Scope("444455556666", "us-east-1"),
            new Scope("444455556666", "us-west-2"));

    @TestHTTPResource("/")
    URI endpoint;

    @InjectMock
    LambdaService lambdaService;

    @Inject
    RequestContext context;

    @Inject
    ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"Invoke", "GET", "POST", "PUT", "DELETE", "PATCH"})
    void heldInvocationsLeaveOtherRequestsServed(String route) throws Exception {
        boolean invoke = "Invoke".equals(route);
        CountDownLatch invoking = new CountDownLatch(SCOPES.size());
        Map<String, Held> held = new ConcurrentHashMap<>();
        Map<String, CompletableFuture<InvokeResult>> releases = new ConcurrentHashMap<>();
        assertEquals(FourWorkerThreadsProfile.WORKER_THREADS, SCOPES.size());
        for (Scope scope : SCOPES) {
            releases.put(scope.target(invoke), new CompletableFuture<>());
        }
        // An unknown URL id gets null, which the controller answers with a 404 the await below reports.
        when(lambdaService.getTargetByUrlId(anyString())).thenAnswer(call -> SCOPES.stream()
                .filter(candidate -> candidate.identity().equals(call.getArgument(0)))
                .findFirst()
                .map(LambdaSynchronousInvokeDispatchIntegrationTest::function)
                .orElse(null));
        // Each answer only records what it saw and waits for its own release; the checks run on the
        // test thread, so a mismatch fails there with its own message instead of a 500 to the client.
        doAnswer(call -> hold(call.getArgument(1), call.getArgument(4), held, invoking, releases))
                .when(lambdaService).invoke(anyString(), anyString(), any(), any(), any(), any(), any());
        doAnswer(call -> hold(call.getArgument(0), call.getArgument(2), held, invoking, releases))
                .when(lambdaService).invokeArn(anyString(), any(), any());

        List<CompletableFuture<HttpResponse<String>>> invocations = new ArrayList<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            try {
                for (Scope scope : SCOPES) {
                    String path = invoke
                            ? "2015-03-31/functions/" + scope.functionName() + "/invocations"
                            : "lambda-url/" + scope.identity() + "/check";
                    invocations.add(client.sendAsync(signed(endpoint.resolve(path), scope, "lambda")
                            .header("Content-Type", "application/json")
                            .method(invoke ? "POST" : route, HttpRequest.BodyPublishers.ofString("{}"))
                            .build(), HttpResponse.BodyHandlers.ofString()));
                }
                assertTrue(invoking.await(10, TimeUnit.SECONDS),
                        () -> "every invocation reaches LambdaService; answered without it: " + answered(invocations));
                assertEquals(releases.keySet(), held.keySet(), "each function is invoked once, by its own target");
                for (Scope scope : SCOPES) {
                    Held invocation = held.get(scope.target(invoke));
                    assertEquals(InvocationType.RequestResponse, invocation.type());
                    assertEquals(scope, invocation.scope(), "an invocation runs in the account and Region of its function");
                }

                // The health check and SSM need a worker; DynamoDB and EC2 run on virtual threads.
                HttpResponse<String> health = served(client.sendAsync(
                        HttpRequest.newBuilder(endpoint.resolve("health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString()));
                assertEquals(200, health.statusCode());
                assertEquals("community", mapper.readTree(health.body()).path("edition").asText());
                HttpResponse<String> parameters = served(client.sendAsync(signed(endpoint, SCOPES.getFirst(), "ssm")
                        .header("Content-Type", "application/x-amz-json-1.1")
                        .header("X-Amz-Target", "AmazonSSM.DescribeParameters")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(), HttpResponse.BodyHandlers.ofString()));
                assertEquals(200, parameters.statusCode());
                assertTrue(mapper.readTree(parameters.body()).path("Parameters").isArray());
                HttpResponse<String> tables = served(client.sendAsync(signed(endpoint, SCOPES.getFirst(), "dynamodb")
                        .header("Content-Type", "application/x-amz-json-1.0")
                        .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(), HttpResponse.BodyHandlers.ofString()));
                assertEquals(200, tables.statusCode());
                assertTrue(mapper.readTree(tables.body()).path("TableNames").isArray());
                HttpResponse<String> regions = served(client.sendAsync(signed(endpoint, SCOPES.getFirst(), "ec2")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("Action=DescribeRegions&Version=2016-11-15"))
                        .build(), HttpResponse.BodyHandlers.ofString()));
                assertEquals(200, regions.statusCode());
                assertTrue(regions.body().contains("<DescribeRegionsResponse"));
                for (CompletableFuture<HttpResponse<String>> invocation : invocations) {
                    assertFalse(invocation.isDone(), "the invocations are still waiting for their functions");
                }

                for (int i = 0; i < SCOPES.size(); i++) {
                    Scope scope = SCOPES.get(i);
                    InvokeResult result = new InvokeResult(200, null, ("{\"statusCode\":201,\"body\":\""
                            + scope.identity() + "\"}").getBytes(StandardCharsets.UTF_8), null, "req-" + i);
                    result.setExecutedVersion("1");
                    releases.get(scope.target(invoke)).complete(result);
                    HttpResponse<String> response = invocations.get(i).get(10, TimeUnit.SECONDS);
                    if (invoke) {
                        assertEquals(200, response.statusCode());
                        assertEquals("1", response.headers().firstValue("X-Amz-Executed-Version").orElseThrow());
                        assertEquals(scope.identity(), mapper.readTree(response.body()).path("body").asText());
                    } else {
                        assertEquals(201, response.statusCode());
                        assertEquals(scope.identity(), response.body());
                    }
                }
            } finally {
                for (CompletableFuture<InvokeResult> release : releases.values()) {
                    release.complete(new InvokeResult(200, null, null, null, "released"));
                }
            }
        }
    }

    private InvokeResult hold(String target, InvocationType type, Map<String, Held> held,
                              CountDownLatch invoking, Map<String, CompletableFuture<InvokeResult>> releases)
            throws Exception {
        held.put(target, new Held(type, new Scope(context.getAccountId(), context.getRegion())));
        invoking.countDown();
        CompletableFuture<InvokeResult> release = releases.get(target);
        return release == null ? new InvokeResult(200, null, null, null, "unexpected")
                : release.get(20, TimeUnit.SECONDS);
    }

    private static LambdaFunction function(Scope scope) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(scope.functionName());
        function.setFunctionArn(scope.functionArn());
        function.setAccountId(scope.account());
        return function;
    }

    /** The status and body of each request that was answered while it should have been waiting. */
    private static List<String> answered(List<CompletableFuture<HttpResponse<String>>> invocations) {
        List<String> answers = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> invocation : invocations) {
            HttpResponse<String> response = invocation.getNow(null);
            if (response != null) {
                answers.add(response.statusCode() + " " + response.body());
            }
        }
        return answers;
    }

    private static HttpRequest.Builder signed(URI uri, Scope scope, String service) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", EnforcementFixtures.auth(scope.account(), scope.region(), service));
    }

    /** Fails when a request is not answered in five seconds, which is what a held worker pool causes. */
    private static <T> HttpResponse<T> served(CompletableFuture<HttpResponse<T>> request) {
        return assertDoesNotThrow(() -> request.get(5, TimeUnit.SECONDS),
                "a request is answered while the invocations wait");
    }

    /** What a held invocation saw: how it was invoked and the account and Region it ran in. */
    private record Held(InvocationType type, Scope scope) {
    }

    private record Scope(String account, String region) {
        String identity() {
            return account + "-" + region;
        }

        String functionName() {
            return "held-" + identity();
        }

        String functionArn() {
            return "arn:aws:lambda:" + region + ":" + account + ":function:" + functionName();
        }

        /** What LambdaService receives: the name from Invoke, the ARN from a function URL. */
        String target(boolean invoke) {
            return invoke ? functionName() : functionArn();
        }
    }
}
