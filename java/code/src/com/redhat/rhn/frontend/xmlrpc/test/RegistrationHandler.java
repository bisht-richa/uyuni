/*
 * Copyright (c) 2009--2010 Red Hat, Inc.
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

package com.redhat.rhn.frontend.xmlrpc.test;

import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.frontend.xmlrpc.BaseHandler;

/**
 * xmlrpc handler for the up2date registration process
 * @apidoc.ignore
 */
public class RegistrationHandler extends BaseHandler {

    @Override
    protected void ensureRoleBasedAccess(User user, String className, String methodName) {
        // Bypass RBAC for unit tests
    }

    /**
     * Returns the RHN privacy statement
     *
     * @return privacy statement string
     * @exception Exception if an error occurs
     */
    public String privacyStatement() {
        return "This is a privacy statement!";
    }
}
