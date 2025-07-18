# -*- coding: utf-8 -*-

# SPDX-FileCopyrightText: 2016-2025 SUSE LLC
#
# SPDX-License-Identifier: Apache-2.0

"""
:codeauthor:    Pablo Suárez Hernández <psuarezhernandez@suse.de>
"""

# pylint: disable-next=unused-import
from unittest.mock import MagicMock, patch
from . import mockery

mockery.setup_environment()

# pylint: disable-next=wrong-import-position
import sys

sys.path.append("../../modules/tops")

# pylint: disable-next=wrong-import-position
import mgr_master_tops

TEST_MANAGER_STATIC_TOP = {
    "base": [
        "channels",
        "certs",
        "packages",
        "custom",
        "custom_groups",
        "custom_org",
        "formulas",
        "services.salt-minion",
        "services.docker",
        "services.kiwi-image-server",
        "ansible",
        "switch_to_bundle.mgr_switch_to_venv_minion",
    ]
}


def test_virtual():
    """
    Test virtual returns the module name
    """
    assert mgr_master_tops.__virtual__() == "mgr_master_tops"


def test_top_default_saltenv():
    """
    Test if top function is returning the static Uyuni top state
    for base environment when no environment has been specified.
    """
    kwargs = {"opts": {"environment": None}}
    assert mgr_master_tops.top(**kwargs) == TEST_MANAGER_STATIC_TOP


def test_top_base_saltenv():
    """
    Test if top function is returning the static Uyuni top state
    for base environment when environment is set to "base".
    """
    kwargs = {"opts": {"environment": "base"}}
    assert mgr_master_tops.top(**kwargs) == TEST_MANAGER_STATIC_TOP


def test_top_unknown_saltenv():
    """
    Test if top function is returning None for unknown salt environments.
    """
    kwargs = {"opts": {"environment": "otherenv"}}
    # pylint: disable-next=singleton-comparison
    assert mgr_master_tops.top(**kwargs) == None
