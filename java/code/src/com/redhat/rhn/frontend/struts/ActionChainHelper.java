/*
 * Copyright (c) 2014 SUSE LLC
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
/**
 * Copyright (c) 2014 Red Hat, Inc.
 */
package com.redhat.rhn.frontend.struts;

import com.redhat.rhn.common.localization.LocalizationService;
import com.redhat.rhn.common.util.DatePicker;
import com.redhat.rhn.domain.action.ActionChain;
import com.redhat.rhn.domain.action.ActionChainFactory;
import com.redhat.rhn.domain.user.User;

import com.suse.utils.Json;

import com.google.gson.JsonObject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.struts.action.DynaActionForm;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

/**
 * Provides helper methods to deal with Action Chains. See also
 * schedule-options.jspf.
 *
 * @author Silvio Moioli {@literal <smoioli@suse.de>}
 */
public class ActionChainHelper {

    /** Name of the form field for the Action Chain label. */
    public static final String LABEL_PROPERTY_NAME = "action_chain";

    /** Name of the parameter for existing Action Chains data field. */
    public static final String EXISTING_ACTION_CHAINS_PROPERTY_NAME =
            "existingActionChains";

    /** Logger instance */
    private static Logger log = LogManager.getLogger(ActionChainHelper.class);

    /**
     * Default constructor.
     */
    private ActionChainHelper() {
    }

    /**
     * Looks in the form contents and returns a (new or existing) ActionChain iff
     * user has selected one in schedule-options.jspf.
     *
     * @param form the form object
     * @param user the user
     * @return the action chain
     */
    public static ActionChain readActionChain(DynaActionForm form, User user) {
        if (DatePicker.ScheduleType.ACTION_CHAIN.asString().equals(form.get(DatePicker.SCHEDULE_TYPE))) {
            String label = sanitizeLabel((String) form.get(LABEL_PROPERTY_NAME));

            if (!StringUtils.isBlank(label)) {
                log.debug("Reading Action Chain from label {}", label);
                return ActionChainFactory.getOrCreateActionChain(label, user);
            }
        }
        return null;
    }

    /**
     * Adds parameters needed to display action chains in schedule-options.jspf
     * to request.
     * @param request the request
     */
    public static void prepopulateActionChains(HttpServletRequest request) {
        log.debug("Prepopulating Action Chains");
        List<Map<String, String>> result = new LinkedList<>();
        User u = new RequestContext(request).getCurrentUser();
        List<ActionChain> actionChains = ActionChainFactory.getActionChainsByModificationDate(u);

        for (ActionChain actionChain : actionChains) {
            populateActionChain(result, actionChain.getLabel());
        }
        if (result.isEmpty()) {
            String placeholder = LocalizationService.getInstance().getMessage(
                "schedule-options.placeholder");
            populateActionChain(result, placeholder);
        }

        request.setAttribute(EXISTING_ACTION_CHAINS_PROPERTY_NAME, Json.GSON.toJson(result));
    }

    /**
     * Adds an Action Chain label to a result map.
     * @param result a result map
     * @param label an Action Chain label
     */
    private static void populateActionChain(List<Map<String, String>> result,
        String label) {
        Map<String, String> map = new HashMap<>();
        map.put("id", label);
        map.put("text", label);
        result.add(map);
    }

    /**
     * Removes illegal characters from an Action Chain label string.
     * @param label the label
     * @return the sanitized label
     */
    public static String sanitizeLabel(String label) {
        return label.replaceAll("[,\"]", "");
    }

    /**
     * Get the action chains available to the give user in JSON.
     * @param user the user
     * @return JSON list with action chain objects
     */
    public static String actionChainsJson(User user) {
        return Json.GSON.toJson(ActionChainFactory.getActionChains(user)
                .stream().map(ac -> {
                    JsonObject tuple = new JsonObject();
                    tuple.addProperty("id", ac.getId());
                    tuple.addProperty("text", ac.getLabel());
                    return tuple;
                }).collect(Collectors.toList()));
    }
}
