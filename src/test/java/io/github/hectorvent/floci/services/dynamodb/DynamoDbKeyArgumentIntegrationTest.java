package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * A Key argument names the key attributes and nothing else. AWS rejects any extra
 * attribute on it, on every surface that takes a Key (characterised against real AWS,
 * us-east-1, 2026-09-17), while an item body is expected to carry the rest of the item.
 */
@QuarkusTest
class DynamoDbKeyArgumentIntegrationTest {

    private static final String SCHEMA_MISMATCH = "The provided key element does not match the schema";

    private static final String TABLE = "KeyArgument-" + UUID.randomUUID();

    private static int testPort;
    private static boolean tableCreated;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // The test port is only bound once the first test starts, so the fixture is created
    // from a @BeforeEach rather than a @BeforeAll.
    @BeforeEach
    void createTable() {
        testPort = RestAssured.port;
        if (tableCreated) {
            return;
        }
        tableCreated = true;
        request("CreateTable", Map.of(
                "TableName", TABLE,
                "KeySchema", List.of(
                        Map.of("AttributeName", "pk", "KeyType", "HASH"),
                        Map.of("AttributeName", "sk", "KeyType", "RANGE")),
                "AttributeDefinitions", List.of(
                        Map.of("AttributeName", "pk", "AttributeType", "S"),
                        Map.of("AttributeName", "sk", "AttributeType", "S")),
                "BillingMode", "PAY_PER_REQUEST")).statusCode(200);
        request("PutItem", Map.of("TableName", TABLE, "Item", Map.of(
                "pk", s("p1"), "sk", s("s1"), "extra", s("hello")))).statusCode(200);
    }

    @AfterAll
    static void deleteTable() {
        given()
                .port(testPort)
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType("application/x-amz-json-1.0")
                .body(Map.of("TableName", TABLE))
                .post("/")
                .then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetItem", "DeleteItem", "UpdateItem", "BatchGetItem", "BatchWriteItem"})
    void extraAttributeOnAKeyArgumentIsRejected(String action) {
        request(action, keyRequest(action, Map.of("pk", s("p1"), "sk", s("s1"), "bogus", s("x"))))
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(SCHEMA_MISMATCH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TransactGetItems", "TransactWriteItems"})
    void extraAttributeInATransactionCancelsTheTransaction(String action) {
        request(action, keyRequest(action, Map.of("pk", s("p1"), "sk", s("s1"), "bogus", s("x"))))
                .statusCode(400)
                .body("__type", equalTo("TransactionCanceledException"))
                .body("CancellationReasons[0].Code", equalTo("ValidationError"))
                .body("CancellationReasons[0].Message", equalTo(SCHEMA_MISMATCH));
    }

    // The extra attribute is rejected whether or not the item it names exists, and whether
    // or not the key itself matches a stored item.
    @Test
    void extraAttributeIsRejectedEvenWhenItNamesAStoredAttribute() {
        getItem(Map.of("pk", s("p1"), "sk", s("s1"), "extra", s("hello")))
                .statusCode(400).body("message", equalTo(SCHEMA_MISMATCH));
        getItem(Map.of("pk", s("absent"), "sk", s("s1"), "bogus", s("x")))
                .statusCode(400).body("message", equalTo(SCHEMA_MISMATCH));
    }

    @Test
    void aKeyNamingOnlyUnknownAttributesIsRejected() {
        getItem(Map.of("bogus", s("x"))).statusCode(400).body("message", equalTo(SCHEMA_MISMATCH));
    }

    // AWS validates the wire shape of every member of a Key, extra attributes included,
    // before it looks at the schema.
    @Test
    void aMalformedExtraAttributeIsReportedAheadOfTheSchemaMismatch() {
        getItem(Map.of("pk", s("p1"), "sk", s("s1"), "bogus", Map.of()))
                .statusCode(400)
                .body("message", equalTo("Supplied AttributeValue is empty, "
                        + "must contain exactly one of the supported datatypes"));
        getItem(Map.of("pk", s("p1"), "sk", s("s1"), "bogus", Map.of("S", "a", "N", "1")))
                .statusCode(400)
                .body("message", equalTo("Supplied AttributeValue has more than one datatypes set, "
                        + "must contain exactly one of the supported datatypes"));
    }

    // The key attributes themselves are validated before the extra attribute is reported.
    @Test
    void anInvalidKeyAttributeIsReportedAheadOfTheExtraAttribute() {
        getItem(Map.of("pk", s(""), "sk", s("s1"), "bogus", s("x")))
                .statusCode(400)
                .body("message", equalTo("One or more parameter values are not valid. The AttributeValue "
                        + "for a key attribute cannot contain an empty string value. Key: pk"));
    }

    // A missing table is reported before the key is looked at.
    @Test
    void aMissingTableIsReportedAheadOfTheExtraAttribute() {
        request("GetItem", Map.of("TableName", "KeyArgument-absent",
                "Key", Map.of("pk", s("p1"), "sk", s("s1"), "bogus", s("x"))))
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    // An item body is not a Key argument: the extra attributes are the item.
    @Test
    void anItemBodyKeepsItsNonKeyAttributes() {
        request("PutItem", Map.of("TableName", TABLE, "Item", Map.of(
                "pk", s("p2"), "sk", s("s2"), "extra", s("kept")))).statusCode(200);
        request("BatchWriteItem", Map.of("RequestItems", Map.of(TABLE, List.of(
                Map.of("PutRequest", Map.of("Item", Map.of(
                        "pk", s("p3"), "sk", s("s3"), "extra", s("kept")))))))).statusCode(200);

        getItem(Map.of("pk", s("p2"), "sk", s("s2"))).statusCode(200)
                .body("Item.extra.S", equalTo("kept"));
        getItem(Map.of("pk", s("p3"), "sk", s("s3"))).statusCode(200)
                .body("Item.extra.S", equalTo("kept"));
    }

    private static ValidatableResponse getItem(Map<String, ?> key) {
        return request("GetItem", Map.of("TableName", TABLE, "Key", key));
    }

    private static Map<String, ?> keyRequest(String action, Map<String, ?> key) {
        return switch (action) {
            case "BatchGetItem" -> Map.of("RequestItems", Map.of(TABLE, Map.of("Keys", List.of(key))));
            case "BatchWriteItem" -> Map.of("RequestItems",
                    Map.of(TABLE, List.of(Map.of("DeleteRequest", Map.of("Key", key)))));
            case "UpdateItem" -> Map.of("TableName", TABLE, "Key", key,
                    "UpdateExpression", "SET touched = :v",
                    "ExpressionAttributeValues", Map.of(":v", s("1")));
            case "TransactGetItems" -> Map.of("TransactItems",
                    List.of(Map.of("Get", Map.of("TableName", TABLE, "Key", key))));
            case "TransactWriteItems" -> Map.of("TransactItems",
                    List.of(Map.of("Delete", Map.of("TableName", TABLE, "Key", key))));
            default -> Map.of("TableName", TABLE, "Key", key);
        };
    }

    private static Map<String, String> s(String value) {
        return Map.of("S", value);
    }

    private static ValidatableResponse request(String action, Map<String, ?> body) {
        return given()
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0")
                .body(body)
                .post("/")
                .then();
    }
}
