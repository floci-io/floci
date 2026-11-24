#!/usr/bin/env python3
"""Tests for fetch_required_resource_types.py, on a page in the Service Authorization Reference's markup."""
from __future__ import annotations

import pytest

import fetch_required_resource_types as f


def action(name: str, resources: list[str]) -> str:
    """An action's rows, one for each resource type cell, as the reference lays them out."""
    cells = [f'<td tabindex="-1"><p><a href="#list_identitystore-resource-{kind.rstrip("*")}">{kind}</a></p></td>'
             for kind in resources] or ['<td tabindex="-1"><p></p></td>']
    keys = '<td tabindex="-1"><p><a href="#list_identitystore-identitystore_PrimaryRegion">PrimaryRegion</a></p></td>'
    rows = [f'<tr><td rowspan="{len(cells)}" tabindex="-1"><p><span id="list_identitystore-action-{name}"></span>'
            f'<a href="https://docs.aws.amazon.com/x.html">{name}</a></p></td><td rowspan="{len(cells)}">'
            f'<p>Grants permission</p></td>{cells[0]}{keys}<td rowspan="{len(cells)}"><p>Write</p></td></tr>']
    rows += [f"<tr>{cell}{keys}</tr>" for cell in cells[1:]]
    return "".join(rows)


OPERATIONS = ('<table id="d0e1"><thead><tr><th>Operation</th><th>IAM action</th></tr></thead><tr><td>'
              '<span id="list_identitystore-operation-CreateGroup"></span>CreateGroup</td></tr></table>')
PAGE = ('<html><body>' + OPERATIONS + '<table id="w1"><thead><tr><th>Actions</th><th>Description</th>'
        '<th>Resource types (*required)</th><th>Condition keys</th><th>Access level</th></tr></thead>'
        + action("CreateGroup", ["Identitystore*"])
        + action("DeleteGroupMembership", ["Group*", "GroupMembership*", "Identitystore*", "User*"])
        + action("ListIdentityStores", [])
        + action("CreateCacheCluster", ["cluster", "parametergroup*"])
        + '</table><table><thead><tr><th>Resource types</th><th>ARN</th></tr></thead></table></body></html>')


def test_each_action_keeps_its_resource_type_cells_as_they_stand():
    assert f.read_actions("page", PAGE) == {
        "CreateGroup": ["Identitystore*"],
        "DeleteGroupMembership": ["Group*", "GroupMembership*", "Identitystore*", "User*"],
        "ListIdentityStores": [],
        "CreateCacheCluster": ["cluster", "parametergroup*"]}


def test_a_page_whose_layout_changed_is_refused():
    with pytest.raises(ValueError, match="no table headed"):
        f.read_actions("page", "<html><body>" + OPERATIONS + "</body></html>")
    with pytest.raises(ValueError, match="names no action"):
        f.read_actions("page", PAGE.replace('<span id="list_identitystore-action-', '<span id="other-'))


def test_the_file_is_stale_when_a_page_says_something_else():
    vendored = f.build({"identitystore": PAGE})
    assert vendored["namespaces"]["identitystore"]["page"] == f.PAGES["identitystore"]
    assert not f.stale(vendored, {"identitystore": PAGE})
    assert f.stale(vendored, {"identitystore": PAGE.replace(">User*<", ">User<")})
