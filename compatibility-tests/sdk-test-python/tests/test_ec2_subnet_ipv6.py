"""CreateSubnet and DescribeSubnets return a fully expanded IPv6 subnet block compressed."""

import ipaddress
import time

from botocore.exceptions import ClientError


def _eventually(call, not_found_code):
    """Retries a describe call that can briefly miss a resource that was just created."""
    for _ in range(30):
        try:
            return call()
        except ClientError as error:
            if error.response["Error"]["Code"] != not_found_code:
                raise
        time.sleep(1)
    return call()


def _associated_ipv6_block(ec2_client, vpc_id):
    """The VPC's Amazon-provided block, once its association is no longer pending."""
    for _ in range(60):
        vpc = _eventually(lambda: ec2_client.describe_vpcs(VpcIds=[vpc_id]),
                          "InvalidVpcID.NotFound")["Vpcs"][0]
        for association in vpc.get("Ipv6CidrBlockAssociationSet", []):
            if association["Ipv6CidrBlockState"]["State"] == "associated":
                return association["Ipv6CidrBlock"]
        time.sleep(1)
    raise AssertionError(f"{vpc_id} has no associated IPv6 block")


def test_subnet_ipv6_cidr_block_is_returned_compressed(ec2_client):
    vpc_id = ec2_client.create_vpc(
        CidrBlock="10.94.0.0/16", AmazonProvidedIpv6CidrBlock=True)["Vpc"]["VpcId"]
    subnet_id = None
    try:
        vpc_block = ipaddress.IPv6Network(_associated_ipv6_block(ec2_client, vpc_id))
        network = next(vpc_block.subnets(new_prefix=64))
        requested = network.network_address.exploded + "/64"

        subnet = ec2_client.create_subnet(
            VpcId=vpc_id, CidrBlock="10.94.1.0/24", Ipv6CidrBlock=requested)["Subnet"]
        subnet_id = subnet["SubnetId"]
        created = [a["Ipv6CidrBlock"] for a in subnet["Ipv6CidrBlockAssociationSet"]]
        assert created == [str(network)]

        described = _eventually(lambda: ec2_client.describe_subnets(SubnetIds=[subnet_id]),
                                "InvalidSubnetID.NotFound")["Subnets"]
        assert [a["Ipv6CidrBlock"] for a in described[0]["Ipv6CidrBlockAssociationSet"]] == created
    finally:
        if subnet_id is not None:
            ec2_client.delete_subnet(SubnetId=subnet_id)
        ec2_client.delete_vpc(VpcId=vpc_id)
