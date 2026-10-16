package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code AWS::S3::Bucket} whose {@code BucketName} changes is replaced, as on AWS: the new bucket
 * is created, the resources that reference it move to it, and the old bucket is deleted once the
 * update commits, unless {@code UpdateReplacePolicy} is {@code Retain}. A later resource failing the
 * update deletes the new bucket and points the stack at the original again. Every template carries a
 * bucket policy and outputs that reference the bucket.
 */
@QuarkusTest
class CloudFormationS3BucketReplacementIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @CsvSource(nullValues = "none", textBlock = """
            none,   none,   false, false
            none,   Retain, true,  false
            Retain, none,   false, true
            Retain, Retain, true,  true
            """)
    void aCommittedRenameAppliesTheReplaceAndDeletionPolicies(String deletionPolicy, String updateReplacePolicy,
                                                              boolean originalKept, boolean replacementKept)
            throws JsonProcessingException {
        String stack = unique("cfn-rename");
        String original = stack + "-old";
        String replacement = stack + "-new";
        deploy("CreateStack", stack, template(original, deletionPolicy, updateReplacePolicy, Map.of(), false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());

        deploy("UpdateStack", stack, template(replacement, deletionPolicy, updateReplacePolicy, Map.of(), false));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_COMPLETE", state.status(), state.reason());
        assertBucket(replacement, true);
        assertBucket(original, originalKept);
        assertEquals(Map.of("Bucket", replacement, "Policy", replacement), physicalIds(stack));
        assertEquals(replacement, outputs(stack).get("BucketName"));
        assertEquals("arn:aws:s3:::" + replacement, outputs(stack).get("BucketArn"));
        given().get("/" + replacement + "?policy").then().statusCode(200)
                .body(containsString("arn:aws:s3:::" + replacement + "/*"));
        assertTrue(stackStatuses(stack).contains("UPDATE_COMPLETE_CLEANUP_IN_PROGRESS"), stackStatuses(stack).toString());
        // The old bucket is deleted in the cleanup phase, once the update has committed. DeletionPolicy
        // does not keep it: that attribute governs the stack delete, UpdateReplacePolicy the replacement.
        assertEquals(originalKept ? List.of() : List.of("DELETE_IN_PROGRESS", "DELETE_COMPLETE"),
                deleteStatuses(stack, "Bucket", original));
        if (originalKept) {
            // The policy resource was replaced too, and nothing keeps the old one: its document is
            // removed from the bucket the stack no longer manages.
            given().get("/" + original + "?policy").then().statusCode(404);
        }

        deleteStack(stack);
        assertBucket(replacement, replacementKept);
        assertBucket(original, originalKept);
        removeBucket(replacement, replacementKept);
        removeBucket(original, originalKept);
    }

    @Test
    void aRenameThatALaterResourceFailsRestoresTheOriginalBucket() throws JsonProcessingException {
        String stack = unique("cfn-rename-rollback");
        String original = stack + "-old";
        String replacement = stack + "-new";
        deploy("CreateStack", stack, template(original, null, null, Map.of(), false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        given().body("kept document").put("/" + original + "/document").then().statusCode(200);

        deploy("UpdateStack", stack, template(replacement, null, null, Map.of(), true));

        // The stack reports UPDATE_FAILED for a moment before it rolls back, so the rollback is waited out.
        CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");
        assertBucket(replacement, false);
        given().get("/" + original + "/document").then().statusCode(200).body(equalTo("kept document"));
        given().get("/" + original + "?policy").then().statusCode(200)
                .body(containsString("arn:aws:s3:::" + original + "/*"));
        assertEquals(Map.of("Bucket", original, "Policy", original), physicalIds(stack));
        assertEquals(original, outputs(stack).get("BucketName"));
        assertEquals("arn:aws:s3:::" + original, outputs(stack).get("BucketArn"));

        // The rollback left nothing behind that a later rename would trip over.
        given().delete("/" + original + "/document").then().statusCode(204);
        deploy("UpdateStack", stack, template(replacement, null, null, Map.of(), false));
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_COMPLETE", state.status(), state.reason());
        assertBucket(original, false);
        assertBucket(replacement, true);
        deleteStack(stack);
        assertBucket(replacement, false);
    }

    /**
     * In us-east-1 CreateBucket also answers for a bucket the account already owns. A rename onto
     * one fails before changing anything, as CreateBucket does in every other region, rather than
     * adopting the bucket, rewriting its configuration and later deleting it as the stack's own.
     */
    @Test
    void aRenameOntoABucketTheAccountAlreadyOwnsLeavesThatBucketAlone() throws JsonProcessingException {
        String stack = unique("cfn-rename-owned");
        String original = stack + "-old";
        String taken = stack + "-taken";
        given().put("/" + taken).then().statusCode(200);
        given().body("not the stack's").put("/" + taken + "/document").then().statusCode(200);
        given().body("""
                <CORSConfiguration><CORSRule><AllowedOrigin>https://owner.example</AllowedOrigin>
                  <AllowedMethod>GET</AllowedMethod></CORSRule></CORSConfiguration>
                """).put("/" + taken + "?cors").then().statusCode(200);
        deploy("CreateStack", stack, template(original, null, null, Map.of(), false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());

        deploy("UpdateStack", stack, template(taken, null, null, Map.of(), false));

        CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals("Your previous request to create the named bucket succeeded and you already own it.",
                updateFailure(stack, "Bucket"));
        given().get("/" + taken + "/document").then().statusCode(200).body(equalTo("not the stack's"));
        given().get("/" + taken + "?cors").then().statusCode(200).body(containsString("https://owner.example"));
        given().get("/" + taken + "?policy").then().statusCode(404);
        assertEquals(Map.of("Bucket", original, "Policy", original), physicalIds(stack));

        deleteStack(stack);
        assertBucket(original, false);
        given().get("/" + taken + "/document").then().statusCode(200).body(equalTo("not the stack's"));
        given().delete("/" + taken + "/document").then().statusCode(204);
        removeBucket(taken, true);
    }

    /** As on AWS, a bucket that still holds objects cannot be deleted; the update reports it and moves on. */
    @Test
    void aCommittedRenameLeavesAnOldBucketThatStillHoldsObjects() throws JsonProcessingException {
        String stack = unique("cfn-rename-full");
        String original = stack + "-old";
        String replacement = stack + "-new";
        deploy("CreateStack", stack, template(original, null, null, Map.of(), false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        given().body("kept document").put("/" + original + "/document").then().statusCode(200);

        deploy("UpdateStack", stack, template(replacement, null, null, Map.of(), false));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_COMPLETE", state.status(), state.reason());
        assertBucket(replacement, true);
        given().get("/" + original + "/document").then().statusCode(200).body(equalTo("kept document"));
        assertEquals(List.of("DELETE_IN_PROGRESS", "DELETE_FAILED"), deleteStatuses(stack, "Bucket", original));
        assertEquals(List.of("The bucket you tried to delete is not empty."),
                eventReasons(stack, "Bucket", original, "DELETE_FAILED"));
        assertEquals(Map.of("Bucket", replacement, "Policy", replacement), physicalIds(stack));

        // The stack no longer manages the old bucket, so its delete leaves it.
        deleteStack(stack);
        assertBucket(replacement, false);
        assertBucket(original, true);
        given().delete("/" + original + "/document").then().statusCode(204);
        removeBucket(original, true);
    }

    /** A rename the new bucket's own configuration fails deletes that bucket and keeps the original. */
    @Test
    void aRenameTheNewBucketItselfFailsDeletesOnlyTheReplacement() throws JsonProcessingException {
        String stack = unique("cfn-rename-invalid");
        String original = stack + "-old";
        String replacement = stack + "-new";
        deploy("CreateStack", stack, template(original, null, null, Map.of(), false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());

        deploy("UpdateStack", stack, template(replacement, null, null,
                Map.of("VersioningConfiguration", Map.of("Status", "Bogus")), false));

        CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");
        assertBucket(replacement, false);
        assertBucket(original, true);
        assertEquals(Map.of("Bucket", original, "Policy", original), physicalIds(stack));
        deleteStack(stack);
        assertBucket(original, false);
    }

    /**
     * A bucket, a policy that references it, outputs with its {@code Ref} and {@code Arn}, and, when
     * {@code fail} is set, a Secrets Manager secret that cannot be created, after both.
     */
    private static String template(String bucket, String deletionPolicy, String updateReplacePolicy,
                                   Map<String, Object> bucketProperties, boolean fail) throws JsonProcessingException {
        Map<String, Object> properties = new LinkedHashMap<>(bucketProperties);
        properties.put("BucketName", bucket);
        Map<String, Object> bucketResource = new LinkedHashMap<>();
        bucketResource.put("Type", "AWS::S3::Bucket");
        if (deletionPolicy != null) {
            bucketResource.put("DeletionPolicy", deletionPolicy);
        }
        if (updateReplacePolicy != null) {
            bucketResource.put("UpdateReplacePolicy", updateReplacePolicy);
        }
        bucketResource.put("Properties", properties);
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("Bucket", bucketResource);
        resources.put("Policy", Map.of("Type", "AWS::S3::BucketPolicy", "Properties", Map.of(
                "Bucket", Map.of("Ref", "Bucket"),
                "PolicyDocument", Map.of("Version", "2012-10-17", "Statement", List.of(Map.of(
                        "Sid", "DenyInsecureTransport", "Effect", "Deny", "Principal", "*", "Action", "s3:*",
                        "Resource", List.of(Map.of("Fn::GetAtt", List.of("Bucket", "Arn")),
                                Map.of("Fn::Sub", "${Bucket.Arn}/*")),
                        "Condition", Map.of("Bool", Map.of("aws:SecureTransport", "false"))))))));
        if (fail) {
            resources.put("Failure", Map.of("Type", "AWS::SecretsManager::Secret",
                    "DependsOn", List.of("Bucket", "Policy"),
                    "Properties", Map.of("SecretString", "explicit",
                            "GenerateSecretString", Map.of("PasswordLength", 32))));
        }
        Map<String, Object> outputs = Map.of(
                "BucketName", Map.of("Value", Map.of("Ref", "Bucket")),
                "BucketArn", Map.of("Value", Map.of("Fn::GetAtt", List.of("Bucket", "Arn"))));
        return MAPPER.writeValueAsString(Map.of("Resources", resources, "Outputs", outputs));
    }

    private static void assertBucket(String bucket, boolean exists) {
        given().head("/" + bucket).then().statusCode(exists ? 200 : 404);
    }

    /** Deletes a bucket a test left behind on purpose, so the run leaves nothing of its own. */
    private static void removeBucket(String bucket, boolean exists) {
        if (exists) {
            given().delete("/" + bucket).then().statusCode(204);
        }
    }

    private static void deploy(String action, String stack, String template) {
        cfn(action, stack).formParam("TemplateBody", template).post("/").then().statusCode(200);
    }

    private static void deleteStack(String stack) {
        cfn("DeleteStack", stack).post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static Map<String, String> outputs(String stack) {
        String body = cfn("DescribeStacks", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "Outputs", "OutputKey", "OutputValue");
    }

    private static Map<String, String> physicalIds(String stack) {
        String body = cfn("DescribeStackResources", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "StackResources", "LogicalResourceId", "PhysicalResourceId");
    }

    /** The stack's events, oldest first. DescribeStackEvents lists the newest first, as AWS does. */
    private static List<Map<String, String>> events(String stack) {
        String body = cfn("DescribeStackEvents", stack).post("/").then().statusCode(200).extract().asString();
        List<Map<String, String>> events = new ArrayList<>(XmlParser.extractGroups(body, "member"));
        Collections.reverse(events);
        return events;
    }

    private static List<String> stackStatuses(String stack) {
        return events(stack).stream()
                .filter(event -> "AWS::CloudFormation::Stack".equals(event.get("ResourceType")))
                .map(event -> event.get("ResourceStatus"))
                .toList();
    }

    /** The delete statuses reported for one physical id of a logical resource, oldest first. */
    private static List<String> deleteStatuses(String stack, String logicalId, String physicalId) {
        return events(stack).stream()
                .filter(event -> logicalId.equals(event.get("LogicalResourceId"))
                        && physicalId.equals(event.get("PhysicalResourceId"))
                        && event.getOrDefault("ResourceStatus", "").startsWith("DELETE_"))
                .map(event -> event.get("ResourceStatus"))
                .toList();
    }

    private static List<String> eventReasons(String stack, String logicalId, String physicalId, String status) {
        return events(stack).stream()
                .filter(event -> logicalId.equals(event.get("LogicalResourceId"))
                        && physicalId.equals(event.get("PhysicalResourceId"))
                        && status.equals(event.get("ResourceStatus")))
                .map(event -> event.getOrDefault("ResourceStatusReason", ""))
                .toList();
    }

    /** Why a resource failed the update, from its UPDATE_FAILED event. */
    private static String updateFailure(String stack, String logicalId) {
        return events(stack).stream()
                .filter(event -> logicalId.equals(event.get("LogicalResourceId"))
                        && "UPDATE_FAILED".equals(event.get("ResourceStatus")))
                .map(event -> event.get("ResourceStatusReason"))
                .findFirst()
                .orElse("");
    }

    private static RequestSpecification cfn(String action, String stack) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action).formParam("StackName", stack);
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }
}
