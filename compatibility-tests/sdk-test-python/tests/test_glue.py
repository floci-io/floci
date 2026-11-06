"""Glue integration tests."""


def test_create_database_with_tags_visible_via_resource_groups_tagging_api(
    glue_client, resourcegroupstaggingapi_client, unique_name
):
    """Test Glue CreateDatabase tags are discoverable via GetResources."""
    database_name = f"glue-db-{unique_name}"
    database_arn = (
        f"arn:aws:glue:{glue_client.meta.region_name}:000000000000:database/{database_name}"
    )

    try:
        glue_client.create_database(
            DatabaseInput={
                "Name": database_name,
                "Description": "python glue database tags",
            },
            Tags={"Environment": "dev", "Project": "project1"},
        )

        response = resourcegroupstaggingapi_client.get_resources(
            ResourceARNList=[database_arn]
        )

        mappings = response["ResourceTagMappingList"]
        assert len(mappings) == 1
        assert mappings[0]["ResourceARN"] == database_arn
        tags = {tag["Key"]: tag["Value"] for tag in mappings[0]["Tags"]}
        assert tags["Environment"] == "dev"
        assert tags["Project"] == "project1"
    finally:
        glue_client.delete_database(Name=database_name)
