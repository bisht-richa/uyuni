/*
 * Copyright (c) 2009--2014 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.redhat.rhn.frontend.xmlrpc.kickstart.tree.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.domain.channel.Channel;
import com.redhat.rhn.domain.channel.test.ChannelFactoryTest;
import com.redhat.rhn.domain.kickstart.KickstartData;
import com.redhat.rhn.domain.kickstart.KickstartFactory;
import com.redhat.rhn.domain.kickstart.KickstartInstallType;
import com.redhat.rhn.domain.kickstart.KickstartableTree;
import com.redhat.rhn.domain.kickstart.test.KickstartDataTest;
import com.redhat.rhn.domain.kickstart.test.KickstartableTreeTest;
import com.redhat.rhn.frontend.dto.kickstart.KickstartableTreeDetail;
import com.redhat.rhn.frontend.xmlrpc.kickstart.KickstartHandler;
import com.redhat.rhn.frontend.xmlrpc.kickstart.tree.KickstartTreeHandler;
import com.redhat.rhn.frontend.xmlrpc.test.BaseHandlerTestCase;
import com.redhat.rhn.testing.TestUtils;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

/**
 * KickstartHandlerTest
 */
public class KickstartTreeHandlerTest extends BaseHandlerTestCase {

    private KickstartTreeHandler handler = new KickstartTreeHandler();
    private KickstartHandler ksHandler = new KickstartHandler();

    @Test
    public void testListKickstartableTrees() throws Exception {
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);
        KickstartableTree testTree = KickstartableTreeTest.
            createTestKickstartableTree(baseChan);
        List ksTrees = handler.list(admin,
                baseChan.getLabel());
        assertFalse(ksTrees.isEmpty());

        boolean found = false;
        for (Object ksTreeIn : ksTrees) {
            KickstartableTree t = (KickstartableTree) ksTreeIn;
            if (t.getId().equals(testTree.getId())) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    public void testCreateKickstartableTree() throws Exception {
        String label = TestUtils.randomString();
        List trees = KickstartFactory.
            lookupAccessibleTreesByOrg(admin.getOrg());
        int origCount = 0;
        if (trees != null) {
            origCount = trees.size();
        }
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);
        String kernelOptions = "self_update=0";
        String postKernelOptions = "self_update=1";
        handler.create(admin, label,
                KickstartableTreeTest.KICKSTART_TREE_PATH.getAbsolutePath(),
                baseChan.getLabel(), KickstartInstallType.RHEL_6, kernelOptions, postKernelOptions);
        KickstartableTreeDetail details = handler.getDetails(admin, label);
        assertEquals(origCount + 1, KickstartFactory.
                lookupAccessibleTreesByOrg(admin.getOrg()).size());
        assertEquals(details.getBasePath(), KickstartableTreeTest.KICKSTART_TREE_PATH.getAbsolutePath());
        assertEquals(details.getChannel().getLabel(), baseChan.getLabel());
        assertEquals(details.getInstallType().getLabel(), KickstartInstallType.RHEL_6);
        assertEquals(details.getKernelOptions(), kernelOptions);
        assertEquals(details.getKernelOptionsPost(), postKernelOptions);
    }

    @Test
    public void testEditKickstartableTree() throws Exception {
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);
        KickstartableTree testTree = KickstartableTreeTest.
            createTestKickstartableTree(baseChan);
        String newBase = "/tmp/kickstart/new-base-path";
        KickstartableTreeTest.createKickstartTreeItems(new File(newBase), admin);
        Channel newChan = ChannelFactoryTest.createTestChannel(admin);
        String kernelOptions = "self_update=0";
        String postKernelOptions = "self_update=1";
        handler.update(admin, testTree.getLabel(),
                newBase, newChan.getLabel(),
                testTree.getInstallType().getLabel(), kernelOptions, postKernelOptions);

        assertEquals(testTree.getBasePath(), newBase);
        assertEquals(testTree.getChannel(), newChan);
        assertNotNull(testTree.getInstallType());
        assertEquals(testTree.getKernelOptions(), kernelOptions);
        assertEquals(testTree.getKernelOptionsPost(), postKernelOptions);
    }

    @Test
    public void testRenameKickstartableTree() throws Exception {
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);
        KickstartableTree testTree = KickstartableTreeTest.
            createTestKickstartableTree(baseChan);
        String newLabel = "newlabel-" + TestUtils.randomString();
        handler.rename(admin, testTree.getLabel(), newLabel);
        assertEquals(newLabel, testTree.getLabel());
    }

    @Test
    public void testDeleteKickstartableTree() throws Exception {
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);
        KickstartableTree testTree = KickstartableTreeTest.
            createTestKickstartableTree(baseChan);
        String label = testTree.getLabel();
        handler.delete(admin, label);
        assertNull(KickstartFactory.lookupKickstartTreeByLabel(label, admin.getOrg()));
    }

    @Test
    public void testDeleteTreeAndProfiles() throws Exception {

        KickstartData ks  = KickstartDataTest.createKickstartWithProfile(admin);
        KickstartableTree testTree = ks.getKickstartDefaults().getKstree();
        Channel channel = testTree.getChannel();

        // verify our setup... should have 1 tree and 1 profile associated w/it
        List ksTrees = handler.list(admin, channel.getLabel());
        List ksProfiles = ksHandler.listKickstarts(admin);
        assertNotNull(ksTrees);
        assertNotNull(ksProfiles);
        int numKsTrees = ksTrees.size();
        int numKsProfiles = ksProfiles.size();

        // execute test...
        int result = handler.deleteTreeAndProfiles(admin, testTree.getLabel());
        assertEquals(1, result);

        // verify that both the tree and associated profile no longer exist
        ksTrees = handler.list(admin, channel.getLabel());
        ksProfiles = ksHandler.listKickstarts(admin);
        assertNotNull(ksTrees);
        assertNotNull(ksProfiles);
        assertEquals(numKsTrees - 1, ksTrees.size());
        assertTrue(ksProfiles.size() < numKsProfiles);
    }

    @Test
    public void testListTreeTypes() {
        List types = handler.listInstallTypes(admin);
        assertNotNull(types);
        assertFalse(types.isEmpty());
        System.out.println("type: " + types.get(0).getClass().getName());
        assertInstanceOf(KickstartInstallType.class, types.get(0));
    }
}
