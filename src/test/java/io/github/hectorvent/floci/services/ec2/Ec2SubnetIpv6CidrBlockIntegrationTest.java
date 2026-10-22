package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * CreateSubnet accepted an Ipv6CidrBlock parameter and silently dropped it: the response always
 * carried an empty ipv6CidrBlockAssociationSet, so a Terraform aws_subnet with an ipv6_cidr_block
 * never converged the same way the VPC-level case fixed in
 * {@link Ec2VpcIpv6CidrBlockIntegrationTest} needed fixing.
 */
@QuarkusTest
class Ec2SubnetIpv6CidrBlockIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void createSubnetWithAnIpv6CidrBlockReturnsItAndKeepsIt() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.94.0.0/16")
            .formParam("AmazonProvidedIpv6CidrBlock", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        String subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.94.1.0/24")
            .formParam("Ipv6CidrBlock", "2600:1f18:1::/64")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.ipv6CidrBlockAssociationSet.item.ipv6CidrBlock",
                    equalTo("2600:1f18:1::/64"))
            .body("CreateSubnetResponse.subnet.ipv6CidrBlockAssociationSet.item.ipv6CidrBlockState.state",
                    equalTo("associated"))
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.ipv6CidrBlockAssociationSet.item.ipv6CidrBlock",
                    equalTo("2600:1f18:1::/64"));
    }

    /**
     * Every spelling of the same /64, expanded, uppercase or with unpadded zero groups, comes back
     * as one RFC 5952 text, compressed and lowercase. The last case has host bits set: AWS
     * documents zeroing them only for the IPv4 block, and Floci applies the same rule here.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "2600:1f18:0003:0000:0000:0000:0000:0000/64",
            "2600:1F18:0003:0000:0000:0000:0000:0000/64",
            "2600:1f18:3:0:0:0:0:0/64",
            "2600:1f18:3::abcd/64"
    })
    void createSubnetStoresItsIpv6CidrBlockInCanonicalForm(String requestedCidr) {
        assertSubnetIpv6CidrBlock(requestedCidr, "2600:1f18:3::/64");
    }

    /**
     * Only a plain IPv6 CIDR is rewritten. A zone id or brackets, which the JDK's address parser
     * accepts, a signed or zero-padded prefix, which Integer.parseInt accepts, and a block that
     * names an IPv4 network leave the value as it was sent.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "2600:1f18:4::%eth0/64",
            "[2600:1f18:4::]/64",
            "2600:1f18:4::/+64",
            "2600:1f18:4::/064",
            "::ffff:10.0.0.0/24",
            "10.0.0.5/24"
    })
    void createSubnetKeepsAnIpv6CidrBlockItDoesNotRewriteAsSent(String requestedCidr) {
        assertSubnetIpv6CidrBlock(requestedCidr, requestedCidr);
    }

    /** CreateSubnet and DescribeSubnets both report {@code expected} for a block sent as {@code requested}. */
    private static void assertSubnetIpv6CidrBlock(String requested, String expected) {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.97.0.0/16")
            .formParam("AmazonProvidedIpv6CidrBlock", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        String subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.97.1.0/24")
            .formParam("Ipv6CidrBlock", requested)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.ipv6CidrBlockAssociationSet.item.ipv6CidrBlock",
                    equalTo(expected))
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.ipv6CidrBlockAssociationSet.item.ipv6CidrBlock",
                    equalTo(expected));
    }

    @Test
    void createSubnetWithoutAnIpv6CidrBlockHasNoIpv6Association() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.95.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.95.1.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<ipv6CidrBlock>")));
    }

    @Test
    void createSubnetRejectsAnIpv6CidrBlockWhenTheVpcHasNone() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.96.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.96.1.0/24")
            .formParam("Ipv6CidrBlock", "2600:1f18:2::/64")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
    }
}
