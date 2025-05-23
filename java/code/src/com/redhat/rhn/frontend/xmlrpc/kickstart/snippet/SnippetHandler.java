/*
 * Copyright (c) 2010--2014 Red Hat, Inc.
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
package com.redhat.rhn.frontend.xmlrpc.kickstart.snippet;


import com.redhat.rhn.domain.kickstart.cobbler.CobblerSnippet;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.frontend.xmlrpc.BaseHandler;
import com.redhat.rhn.manager.kickstart.cobbler.CobblerSnippetLister;

import com.suse.manager.api.ReadOnly;

import java.util.List;


/**
 * KickstartSnippetHandler
 * @apidoc.namespace kickstart.snippet
 * @apidoc.doc Provides methods to create kickstart files
 */
public class SnippetHandler extends BaseHandler {

    /**
     * list all cobbler snippets for a user.  Includes default and custom snippets
     * @param loggedInUser The current user
     * @return List of cobbler snippet objects
     *
     * @apidoc.doc List all cobbler snippets for the logged in user
     * @apidoc.param #session_key()
     * @apidoc.returntype
     *          #return_array_begin()
     *            $SnippetSerializer
     *          #array_end()
     */
    @ReadOnly
    public List<CobblerSnippet> listAll(User loggedInUser) {
        return CobblerSnippetLister.getInstance().list(loggedInUser);
    }

    /**
     * list custom cobbler snippets for a user.
     * @param loggedInUser The current user
     * @return List of cobbler snippet objects
     *
     * @apidoc.doc List only custom snippets for the logged in user.
     *    These snipppets are editable.
     * @apidoc.param #session_key()
     * @apidoc.returntype
     *          #return_array_begin()
     *            $SnippetSerializer
     *          #array_end()
     */
    @ReadOnly
    public List<CobblerSnippet> listCustom(User loggedInUser) {
        return CobblerSnippetLister.getInstance().listCustom(loggedInUser);
    }

    /**
     * list all pre-made default cobbler snippets for a user.
     * @param loggedInUser The current user
     * @return List of cobbler snippet objects
     *
     * @apidoc.doc List only pre-made default snippets for the logged in user.
     *    These snipppets are not editable.
     * @apidoc.param #session_key()
     * @apidoc.returntype
     *          #return_array_begin()
     *            $SnippetSerializer
     *          #array_end()
     */
    @ReadOnly
    public List<CobblerSnippet> listDefault(User loggedInUser) {
        return CobblerSnippetLister.getInstance().listDefault(loggedInUser);
    }


    /**
     * Create or update a snippet.  If the snippet doesn't exist it will be created.
     * @param loggedInUser The current user
     * @param name name of the snippet
     * @param contents the contents of the snippet
     * @return  the snippet
     *
     * @apidoc.doc Will create a snippet with the given name and contents if it
     *      doesn't exist. If it does exist, the existing snippet will be updated.
     * @apidoc.param #session_key()
     * @apidoc.param #param("string", "name")
     * @apidoc.param #param("string", "contents")
     * @apidoc.returntype
     *            $SnippetSerializer
     */
    public CobblerSnippet createOrUpdate(User loggedInUser, String name, String contents) {
        CobblerSnippet snip = CobblerSnippet.loadEditableIfExists(name,
                loggedInUser.getOrg());
        return CobblerSnippet.createOrUpdate(snip == null, name, contents,
                loggedInUser.getOrg());
    }

    /**
     * Delete a snippet.
     * @param loggedInUser The current user
     * @param name the name of the snippet
     * @return 1 for success 0 for not
     *
     * @apidoc.doc Delete the specified snippet.
     *      If the snippet is not found, 0 is returned.
     * @apidoc.param #session_key()
     * @apidoc.param #param("string", "name")
     * @apidoc.returntype
     *            #return_int_success()
     */
    public int delete(User loggedInUser, String name) {
        CobblerSnippet snip = CobblerSnippet.loadEditableIfExists(name,
                loggedInUser.getOrg());
        if (snip != null) {
            snip.delete();
            return 1;
        }
        return 0;
    }


}
