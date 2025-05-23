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
package com.redhat.rhn.manager.configuration;

import com.redhat.rhn.common.db.datasource.CallableMode;
import com.redhat.rhn.common.db.datasource.DataList;
import com.redhat.rhn.common.db.datasource.DataResult;
import com.redhat.rhn.common.db.datasource.ModeFactory;
import com.redhat.rhn.common.db.datasource.Row;
import com.redhat.rhn.common.db.datasource.SelectMode;
import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.common.hibernate.LookupException;
import com.redhat.rhn.common.localization.LocalizationService;
import com.redhat.rhn.common.messaging.MessageQueue;
import com.redhat.rhn.common.security.PermissionException;
import com.redhat.rhn.common.util.StringUtil;
import com.redhat.rhn.domain.action.Action;
import com.redhat.rhn.domain.action.ActionChain;
import com.redhat.rhn.domain.action.ActionFactory;
import com.redhat.rhn.domain.config.ConfigChannel;
import com.redhat.rhn.domain.config.ConfigChannelType;
import com.redhat.rhn.domain.config.ConfigFile;
import com.redhat.rhn.domain.config.ConfigFileCount;
import com.redhat.rhn.domain.config.ConfigFileName;
import com.redhat.rhn.domain.config.ConfigFileSafeDeleteException;
import com.redhat.rhn.domain.config.ConfigFileType;
import com.redhat.rhn.domain.config.ConfigRevision;
import com.redhat.rhn.domain.config.ConfigurationFactory;
import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.recurringactions.RecurringAction;
import com.redhat.rhn.domain.recurringactions.RecurringActionFactory;
import com.redhat.rhn.domain.recurringactions.state.RecurringConfigChannel;
import com.redhat.rhn.domain.recurringactions.type.RecurringState;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerFactory;
import com.redhat.rhn.domain.state.StateFactory;
import com.redhat.rhn.domain.token.ActivationKey;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.frontend.dto.ConfigChannelDto;
import com.redhat.rhn.frontend.dto.ConfigFileDto;
import com.redhat.rhn.frontend.dto.ConfigFileNameDto;
import com.redhat.rhn.frontend.dto.ConfigGlobalDeployDto;
import com.redhat.rhn.frontend.dto.ConfigRevisionDto;
import com.redhat.rhn.frontend.dto.ConfigSystemDto;
import com.redhat.rhn.frontend.dto.LastDeployDto;
import com.redhat.rhn.frontend.dto.ServerActionDto;
import com.redhat.rhn.frontend.events.SsmConfigFilesEvent;
import com.redhat.rhn.frontend.listview.PageControl;
import com.redhat.rhn.manager.BaseManager;
import com.redhat.rhn.manager.action.ActionManager;
import com.redhat.rhn.manager.rhnset.RhnSetDecl;
import com.redhat.rhn.manager.system.SystemManager;
import com.redhat.rhn.taskomatic.TaskomaticApiException;

import com.suse.manager.utils.MinionServerUtils;
import com.suse.manager.webui.services.ConfigChannelSaltManager;
import com.suse.manager.webui.services.SaltStateGeneratorService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ConfigurationManager
 */
public class ConfigurationManager extends BaseManager {

    private static final String CONFIG_QUERIES = "config_queries";
    private static final String USER_ID = "user_id";
    private static final String ORG_ID = "org_id";

    /**
     * Logger for this class
     */
    private static Logger log = LogManager.getLogger(ConfigurationManager.class);

    private static final ConfigurationManager INSTANCE = new ConfigurationManager();

    //These are used when enabling a system for configuration management
    //through our helpful user interface
    public static final int ENABLE_SUCCESS = 0;
    public static final int ENABLE_ERROR_RHNTOOLS = 2;
    public static final int ENABLE_ERROR_PACKAGES = 3;

    public static final String FEATURE_CONFIG = "ftr_config";

    /**
     * Prevent people for making objects of this class.
     */
    private ConfigurationManager() {

    }

    /**
     * @return the static object of this class.
     */
    public static ConfigurationManager getInstance() {
        return INSTANCE;
    }

    /**
     * Saves the ConfigChannel and updates its salt file hierarchy and the assignment states on the disk.
     *
     * @param cc the config channel to save.
     * @param channelOldLabel - the label of the channel before the change.
     *                        Needed for synchronizing the salt file hierarchy.
     */
    public void save(ConfigChannel cc, Optional<String> channelOldLabel) {
        ConfigurationFactory.commit(cc);
        ConfigChannelSaltManager.getInstance()
                .generateConfigChannelFiles(cc, channelOldLabel);

        if (!channelOldLabel.filter(ol -> cc.getLabel().equals(ol)).isPresent()) {
            SaltStateGeneratorService.INSTANCE.regenerateConfigStates(cc);
        }
    }

    /**
     * Saves the ConfigRevision and updates the salt file hierarchy corresponding to the
     * revision channel.
     *
     * @param revision the config file revision
     * @return the saved revision
     */
    public ConfigRevision saveRevision(ConfigRevision revision) {
        ConfigRevision committed = ConfigurationFactory.commit(revision);
        ConfigChannelSaltManager.getInstance().generateConfigChannelFiles(
                revision.getConfigFile().getConfigChannel(), Optional.empty());
        return committed;
    }

    /**
     * List all of the global channels a given user can see.
     * @param user The user looking at channels.
     * @param pc A page control for this user.
     * @return A list of the channels in DTO format.
     */
    public DataResult<ConfigChannelDto> listGlobalChannels(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "overview_config_channels");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * List all of the global channels a given user can see.
     * @param user The user looking at channels.
     * @return A list of the channels.
     */
    public List<ConfigChannel> listGlobalChannels(User user) {
        return ConfigurationFactory.listGlobalChannels().stream()
                .filter(c -> accessToChannel(user.getId(), c.getId()))
                .collect(Collectors.toList());
    }

    /**
     * List all of the global channels a given user can see
     *  in the activaiton keys page.
     * @param key Activation Key whose channesl are to be ignored
     * @param user The user looking at channels.
     * @return A list of the channels in DTO format.
     */
    public DataResult<ConfigChannelDto> listGlobalChannelsForActivationKeySubscriptions(ActivationKey key, User user) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "overview_config_channels_for_act_key_subscriptions");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        params.put("tid", key.getToken().getId());
        Map<String, Object> elabParams = new HashMap<>();
        return makeDataResult(params, elabParams, null, m);
    }

    /**
     * List the global channels in a activation key.
     * used in the ActivationKey config channel subscription page.
     * @param key Activation Key to look up on.
     * @param user The user looking at channels.
     * @return A list of the channels in DTO format.
     */
    public DataResult<ConfigChannelDto> listGlobalChannelsForActivationKey(ActivationKey key, User user) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "overview_config_channels_for_act_key");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        params.put("tid", key.getToken().getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        return makeDataResult(params, elabParams, null, m);
    }
    /**
     * This query lists  all the channels based on system type
     * a user can see along with info on whether the
     * channels are subscribed to a given server
     * Basically used in SDC Subscribe Channels page
     * @param server the server to check the channels
     *                                      subscriptions on
     * @param user The user looking at channels.
     * @param pc A page control for this user.
     * @return A list of the channels in DTO format.
     */
    public DataResult<ConfigChannelDto> listChannelsForSystemSubscriptions(Server server,
                                                                          User user,
                                                                          PageControl pc) {
        if (MinionServerUtils.isMinionServer(server)) {
            return listGlobalChannelsForSystemSubscriptions(server, user, pc);
        }
        else {
            return listNormalChannelsForSystemSubscriptions(server, user, pc);
        }
    }

    /**
     * This query basically lists  all the global channels
     * a user can see along with info on whether the
     * channels are subscribed to a given server
     * Basically used in SDC Subscribe Channels page
     * @param server the server to check the channels
     *                                      subscriptions on
     * @param user The user looking at channels.
     * @param pc A page control for this user.
     * @return A list of the channels in DTO format.
     */
    public DataResult<ConfigChannelDto> listGlobalChannelsForSystemSubscriptions(Server server,
            User user,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "config_channels_for_system_subscriptions");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        params.put("sid", server.getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * This query lists only the the global channels
     * of type 'Normal' a user can see along with info on whether the
     * channels are subscribed to a given server
     * Basically used in SDC Subscribe Channels page
     * @param server the server to check the channels
     *                                      subscriptions on
     * @param user The user looking at channels.
     * @param pc A page control for this user.
     * @return A list of the channels in DTO format.
     */
    public DataResult<ConfigChannelDto> listNormalChannelsForSystemSubscriptions(Server server,
                                             User user,
                                             PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "normal_config_channels_for_system_subscriptions");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        params.put("sid", server.getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists all configuration managed systems along with counts for how many
     * files and channels they are managed by.
     * @param user The user requesting to view managed systems
     * @param pc A page control for this user.
     * @return A list of configged systems in DTO format.
     */
    public DataResult<ConfigSystemDto> listManagedSystemsAndFiles(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "config_managed_systems");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * Returns the given system is config enabled.
     * @param server The system we care abt finding config capability info on
     * @param user The user requesting to view target systems
     * @return true of the system is config capable.
     */
    public boolean isConfigEnabled(Server server, User user) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "system_config_enabled_check");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("sid", server.getId());
        DataResult<Map<String, ? extends Number>> dr = m.execute(params);
        return dr.get(0).get("count").intValue() > 0;
    }


    /**
     * Lists all systems visible to a user that are not configuration managed.
     * Also includes whether the system is currently capable for configuration management.
     * @param user The user requesting to view target systems
     * @param pc A page control for this user.
     * @return A list of non config managed systems in DTO format.
     */
    public DataResult<ConfigSystemDto> listNonManagedSystems(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "non_config_managed_systems");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * Lists all systems visible to a user that are not configuration managed that are in
     * the given set.
     * Also includes whether the system is currently capable for configuration management
     * and what actions are needed in order to make it so.
     * @param user The user requesting to view target systems
     * @param pc A page control for this user.
     * @param set The label for the desired RhnSet
     * @return A list of non config managed systems in DTO format.
     */
    public DataResult<ConfigSystemDto> listNonManagedSystemsInSet(User user, PageControl pc, String set) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "non_config_managed_systems_in_set");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("set", set);
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * Lists all systems visible to a user that are not configuration managed that are in
     * the given set.  Elaborates all of them so that the required actions for enabling
     * config management are given in the list.
     * @param user The user about to enable things
     * @param set The name of the set.
     * @return An elaborated list of non-configuration managed systems in the given set.
     */
    public DataResult<ConfigSystemDto> listNonManagedSystemsInSetElaborate(User user, String set) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "non_config_managed_systems_in_set");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("set", set);
        DataResult<ConfigSystemDto> dr = m.execute(params);
        dr.setTotalSize(dr.size());
        dr.elaborate(new HashMap<>());
        return dr;
    }

    /**
     * Lists all the revisions of the given file other than the given revision.
     * @param user The user requesting a list of revisions.
     * @param file The file that the revisions should be for.
     * @param current The current revision that should not be included in the list.
     * @param pc A PageControl for this user
     * @return A list of revisions.
     */
    public DataResult<ConfigRevisionDto> listRevisionsForCompare(User user, ConfigFile file,
            ConfigRevision current, PageControl pc) {
        if (!current.getConfigFile().getId().equals(file.getId())) {
            throw new IllegalArgumentException("Current revision is not for given file");
        }
        if (!user.getOrg().equals(file.getConfigChannel().getOrg())) {
            throw new IllegalArgumentException("User and file are in different orgs.");
        }
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "compare_revision_list");
        Map<String, Object> params = new HashMap<>();
        params.put("cfid", file.getId());
        params.put("crid", current.getId());
        params.put(USER_ID, user.getId());
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * Lists all the alternatives for a given file in other config channels.
     * @param user The user requesting a list of alternate files.
     * @param current The current file that should not be included in the list.
     * @param pc A PageControl for this user
     * @return A list of alternate files.
     */
    public DataResult<ConfigChannelDto> listAlternateFilesForCompare(User user, ConfigFile current,
            PageControl pc) {
        if (!user.getOrg().equals(current.getConfigChannel().getOrg())) {
            throw new IllegalArgumentException("User and file are in different orgs.");
        }
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "compare_alternate_file_list");
        Map<String, Object> params = new HashMap<>();
        params.put("cfid", current.getId());
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * Lists all the other channels in this org.
     * @param user The user requesting a list of channels.
     * @param pc A PageControl for this user
     * @return A list of channels.
     */
    public DataResult<ConfigChannelDto> listChannelsForFileCompare(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "compare_other_channel_list");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * Gets a list of files (not directories) in the given config channel.
     * @param user The user requesting a list of files.
     * @param channel The config channel
     * @param pc A page control for the user.
     * @return a list of config files in DTO format
     */
    public DataResult<ConfigChannelDto> listFilesInChannel(User user, ConfigChannel channel, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "compare_other_file_list");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("ccid", channel.getId());
        return makeDataResult(params, new HashMap<>(), pc, m);
    }


    /**
     * Get a list of systems for a config file diff action.
     * @param user The user requesting a list of systems.
     * @param cfnid The config file name identifier for the file to diff.
     * @param pc A PageControl for this user.
     * @return A list of systems in DTO format
     */
    public DataResult<ConfigSystemDto> listSystemsForFileCompare(User user, Long cfnid, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "systems_for_diff");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("cfnid", cfnid);
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Get a list of systems to whose local or sandbox channel one could copy a cfg-file
     * @param user The user requesting a list of systems.
     * @param cfnid The config file name identifier for the file to diff.
     * @param chnlType ConfigChannelType to look for (LOCAL or SANDBOX)
     * @param pc A PageControl for this user.
     * @return A list of systems in DTO format
     */
    public DataResult<ConfigSystemDto> listSystemsForFileCopy(
            User user, Long cfnid, ConfigChannelType chnlType, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "systems_for_copy");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("cfnid", cfnid);
        elabParams.put("label", chnlType.getLabel());
        return makeDataResult(params, elabParams, pc, m);
    }
    /**
     * Lists the file names of all files subscribed to by systems in the
     * given user's system_list set.
     * @param user The user requesting the list of file names.
     * @param pc A PageControl for this user.
     * @return A list of config file names in DTO format.
     */
    public DataResult<ConfigFileNameDto> listFileNamesForSsm(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "configfiles_for_ssm");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists the systems in the given user's system_list set that are subscribed to a
     * config channel that contains a config file with the given config file name id.
     * @param user The user requesting a list of systems.
     * @param cfnid The identifier of the config file name
     * @param pc A PageControl for this user.
     * @return A list of systems in DTO format.
     */
    public DataResult<ConfigSystemDto> listSystemsForFileName(User user, Long cfnid, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "systems_in_set_with_file_name");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("cfnid", cfnid);
        params.put("system_set_label", RhnSetDecl.SYSTEMS.getLabel());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("cfnid", cfnid);
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists the systems in the given user's system_list set that are subscribed to a
     * config channel with the given config channel id.
     * @param user The user requesting a list of systems.
     * @param ccid The identifier of the config channel
     * @param pc A PageControl for this user.
     * @return A list of systems in DTO format.
     */
    public DataResult<ConfigSystemDto> listSystemsForConfigChannel(User user, Long ccid, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "systems_for_channel_in_set");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("ccid", ccid);
        params.put("system_set_label", RhnSetDecl.SYSTEMS.getLabel());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("ccid", ccid);
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists the file names in the user's config file name set relevant to the
     * given server. Finds the deployable revisions for each file name.
     * @param user The user requesting a list of file names
     * @param server The server to which these files must be relevant
     * @param pc A PageControl for this user
     * @return A list of config file names in DTO format.
     */
    public DataResult<ConfigFileNameDto> listFileNamesInSetForSystem(User user, Server server,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "file_names_in_set_for_system");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("sid", server.getId());
        params.put("name_set_label", RhnSetDecl.CONFIG_FILE_NAMES.getLabel());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("sid", server.getId());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists the file names in the user's config file name set whether or not they
     * are relevant to the given server. Finds the deployable revisions for each
     * file name for the given server.
     * @param user The user requesting a list of file names
     * @param server The server to which these files may be relevant
     * @param setLabel The DB label of the config file name set.
     * @param pc A PageControl for this user
     * @return A list of config file names in DTO format.
     */
    public DataResult<ConfigFileNameDto> listFileNamesInSet(User user, Server server, String setLabel,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "file_names_in_set");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("name_set_label", setLabel);
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("sid", server.getId());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists the file names to which the given server is subscribed
     * Finds the deployable revisions for each file name.
     * @param server The server to which these files must be relevant
     * @return A list of config file names in DTO format.
     */
    public DataResult<ConfigFileNameDto> listAllFileNamesForSystem(Server server) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "automated_file_names_for_system");
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> elabParams = new HashMap<>();
        params.put("sid", server.getId());
        elabParams.put("sid", server.getId());
        DataResult<ConfigFileNameDto> dr = makeDataResult(params, elabParams, null, m);
        dr.elaborate();
        return dr;
    }

    /**
     * Lists the file names to which the given server is subscribed
     * Finds the deployable revisions for each file name.
     * @param user The user requesting a list of file names
     * @param server The server to which these files must be relevant
     * @param pc A PageControl for this user
     * @return A list of config file names in DTO format.
     */
    public DataResult<ConfigFileNameDto> listFileNamesForSystem(User user, Server server, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "file_names_for_system");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("sid", server.getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("sid", server.getId());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists the file names to which the given server is subscribed
     * Finds the deployable revisions for each file name.
     *
     * There's not really a space in the sql xml file for comments on
     * how the query works, so I'll put them here. The first query I tried
     * was one layer of joins to get the list of file names. However, if a
     * file was in multiple config channels that a server was subscribed too
     * it would show up in the list multiple times. What we really want is
     * to only list the files that can actually appear on the customer's
     * machine. I created a temporary table that groups by file id
     * and selects the min (highest) priority config channel available.
     * Then we do the normal joins to get the actual return values, and we
     * are guaranteed to have a unique list of the files in the highest
     * priority channels.
     *
     * @param user The user requesting a list of file names
     * @param server The server to which these files must be relevant
     * @param pc A PageControl for this user
     * @return A list of config file names in DTO format.
     */
    public DataResult<ConfigFileNameDto> listFileNamesForSystemQuick(User user, Server server, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "file_names_for_system_quick");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("sid", server.getId());
        Map<String, Object> elabParams = new HashMap<>();
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists the file names to which the given server is subscribed by channel
     * Finds the deployable revisions for each file name.
     * @param user The user requesting a list of file names
     * @param server The server to which these files must be relevant
     * @param channel The channel to which these files must be relevant
     * @param pc A PageControl for this user
     * @return A list of config file names in DTO format.
     */
    public DataResult<ConfigFileNameDto> listFileNamesForSystemChannel(User user,
            Server server, ConfigChannel channel, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "file_names_for_system_channel");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("sid", server.getId());
        params.put("ccid", channel.getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("sid", server.getId());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists the config channels in the user's config channel set to which the
     * given server is subscribed. Finds the deployable files for each channel.
     * @param user The user requesting a list of config channels
     * @param server The server subscribed to these channels
     * @param pc A PageControl for this user
     * @return A list of config channels in DTO format.
     */
    public DataResult<ConfigChannelDto> listConfigChannelsForSystem(User user,
            Server server, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "channels_in_set_for_system");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("sid", server.getId());
        params.put("channel_set_label", RhnSetDecl.CONFIG_CHANNELS.getLabel());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("sid", server.getId());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists the systems in the user's system_list set that are subscribed to
     * files whose names are in the user's config file name set.
     * @param user The user requesting the list of file names.
     * @param pc A PageControl for this user.
     * @param feature acl off the list by selecting a config mgmt specific feature
     *          like (configfiles.deploy/configfiles.diff)
     * @return A list of systems in DTO format.
     */
    public DataResult<ConfigSystemDto> listSystemsForConfigAction(User user,
            PageControl pc, String feature) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "config_systems_for_ssm");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("system_set_label", RhnSetDecl.SYSTEMS.getLabel());
        params.put("name_set_label", RhnSetDecl.CONFIG_FILE_NAMES.getLabel());
        params.put("feature", feature);

        Map<String, Object> elabParams = new HashMap<>();
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * List all of the global channels to which systems in the current user's
     * system_list are subscribed.
     * @param user The user looking at channels.
     * @param pc A page control for this user.
     * @return A list of the channels in DTO format.
     */
    public DataResult<ConfigChannelDto> ssmChannelList(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "ssm_config_channels");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists all the global configuration channels to which the given user can subscribe
     * systems. Only channels that it makes sense to subscribe to will be listed. In other
     * words, if all of the servers in the SSM are already subscribed to a channel, it
     * will not be returned. To get this list of already subscribed channels, use
     * {@link #ssmChannelListForSubscribeAlreadySubbed(User)}
     *
     * @param user The user looking at channels.
     * @param pc A page control for this user.
     * @return a list of {@link ConfigChannelDto} objects
     */
    public DataResult<ConfigChannelDto> ssmChannelListForSubscribe(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "ssm_channels_for_subscribe_choose");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        elabParams.put("system_set_label", RhnSetDecl.SYSTEMS.getLabel());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Returns configuration channels that <em>every</em> system in the SSM is subscribed
     * to. This is effectively the complement of
     * {@link #ssmChannelListForSubscribe(User, PageControl)}.
     *
     * @param user the user working with the channels
     * @return a list of {@link ConfigChannelDto} objects
     */
    public DataResult<ConfigChannelDto> ssmChannelListForSubscribeAlreadySubbed(User user) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "ssm_channels_for_subscribe_already_sub");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        elabParams.put("system_set_label", RhnSetDecl.SYSTEMS.getLabel());
        return makeDataResult(params, elabParams, null, m);
    }

    /**
     * List all the global channels to which the given user can subscribe
     * systems.
     * @param user The user looking at channels.
     * @return A list of the channels in DTO format.
     */
    public List<ConfigChannelDto> ssmChannelsInSetForSubscribe(User user) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "ssm_channels_for_subscribe_in_set");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("channel_set_label", RhnSetDecl.CONFIG_CHANNELS.getLabel());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        elabParams.put("system_set_label", RhnSetDecl.SYSTEMS.getLabel());
        return DataList.getDataList(m, params, elabParams);
    }

    /**
     * List the systems in your system set along with the number
     * of channels selected to which they are already subscribed.
     * @param user The user looking at channels.
     * @return A list of the systems in DTO format.
     */
    public List<ConfigSystemDto> ssmSystemsForSubscribe(User user) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "ssm_systems_for_subscribe");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("system_set_label", RhnSetDecl.SYSTEMS.getLabel());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        elabParams.put("channel_set_label",
                RhnSetDecl.CONFIG_CHANNELS_RANKING.getLabel());
        return DataList.getDataList(m, params, elabParams);
    }

    /**
     * List all systems in the given user's system_list subscribed to at
     * least one channel in the user's config channel set
     * @param user The user requested a list of systems
     * @param pc A PageControl for this user
     * @return A list of systems in DTO format
     */
    public DataResult<ConfigSystemDto> ssmSystemListForChannels(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "ssm_systems_for_config_channels");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("system_set_label", RhnSetDecl.SYSTEMS.getLabel());
        params.put("channel_set_label", RhnSetDecl.CONFIG_CHANNELS.getLabel());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        elabParams.put("channel_set_label", RhnSetDecl.CONFIG_CHANNELS.getLabel());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Get a summary of configuration enablement.
     * @param user The user asking for a summary
     * @param pc A PageControl object for this user.
     * @param set The label for the RhnSet where the summary is located.
     * @return The summary for each system in Dto format.
     */
    public DataResult<ConfigSystemDto> getEnableSummary(User user, PageControl pc, String set) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "enable_config_summary");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put("set", set);
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * Lists all global config files in this user's org that this user can view
     * along with system count and overridden count.
     * @param user The user requesting to view config files
     * @param pc A page control for this user.
     * @return A list of global config files that this user can view in DTO format.
     */
    public DataResult<ConfigFileDto> listGlobalConfigFiles(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "global_configfiles_for_user");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Lists all local config files in this user's org that this user can view.
     * @param user The user requesting to view config files
     * @param pc A page control for this user.
     * @return A list of local config files that this user can view in DTO format.
     */
    public DataResult<ConfigFileDto> listLocalConfigFiles(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "local_configfiles_for_user");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * Lists all global config channels in this user's org except the one that
     * contains the given config file.
     * Includes information about files with the same path as the given file in
     * the channels listed.
     * @param user The user requesting to view config files
     * @param current The file to be copied for which we should look for
     *                alternatives in the listed channels.
     *                The list will exclude the channel that this file is in.
     * @param type The database type for the channel.
     *             A label from ConfigurationFactory.CONFIG_CHANNEL_TYPE_*
     * @param pc A page control for this user.
     * @return A list of global config channels in this org in DTO format.
     */
    public DataResult<ConfigChannelDto> listChannelsForFileCopy(User user, ConfigFile current,
            String type, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "channels_for_file_copy");
        Map<String, Object> params = new HashMap<>();
        params.put(ORG_ID, user.getOrg().getId());
        params.put("ccid", current.getConfigChannel().getId());
        params.put("type", type);
        params.put(USER_ID, user.getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("name", current.getConfigFileName().getPath());
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * List cfg-channels OTHER THAN the specified one, that are of the specified type,
     * and are accessible to the specified user
     * @param user user making the request
     * @param cc config-channel of interest
     * @param ccType channel-type of interest
     * @return DataResult of ConfigChannelDto's
     */
    public List<ConfigChannelDto> listChannelsForCopy(User user, ConfigChannel cc, String ccType) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "other_channels");
        Map<String, Object> params = new HashMap<>();
        params.put(ORG_ID, user.getOrg().getId());
        params.put("ccid", cc.getId());
        params.put("type", ccType);
        params.put(USER_ID, user.getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put(USER_ID, user.getId());
        return DataList.getDataList(m, params, elabParams);
    }

    /**
     * List systems accessible to the specified user
     * @param user user making the request
     * @param pc page-control
     * @return DataResult of ConfigSystemDto's
     */
    public DataResult<ConfigSystemDto> listSystemsForCopy(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "list_available_systems");
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        Map<String, Object> elabParams = new HashMap<>();
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Return the number of systems subscribed to the specified channel.
     * @param user user making the request
     * @param channel channel of interest
     * @return number of systems subscribed to channel
     */
    public int getSystemCount(User user, ConfigChannel channel)  {
        checkChannelAccess(user, channel);
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "systems_subscribed_to_channel");
        Map<String, Object> params = new HashMap<>();
        params.put("ccid", channel.getId());
        params.put(USER_ID, user.getId());
        DataResult<Map<String, Object>> dr = m.execute(params);
        Long count = (Long)dr.get(0).get("num_systems");
        return count.intValue();
    }

    // Utility for executing the files_in_channel query given
    // that you've specified the kind-of files you're interested in
    private int doCountFiles(Map<String, Object> params) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "files_in_channel");
        DataResult<Map<String, Object>> dr = m.execute(params);
        Long count = (Long)dr.get(0).get("num_files");
        return count.intValue();
    }

    /**
     * Return the number of bytes used for all revisions of the specified ConfigFile
     * @param user User making the request
     * @param file File of interest
     * @return total bytes of all ConfigRevisions (0 for directories)
     */
    public int getFileStorage(User user, ConfigFile file) {
        Map<String, Object> params = new HashMap<>();
        params.put("cfid", file.getId());
        params.put(USER_ID, user.getId());
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "configfile_revisions_size");
        DataResult<Map<String, Object>> dr = m.execute(params);
        Long count = (Long)dr.get(0).get("total_file_size");
        return count.intValue();
    }

    /**
     * Return the number of Symlinks in this config-channel
     * @param user user making the request
     * @param channel channel of interest
     * @return number of symlinks in this channel
     */
    public int getSymlinkCount(User user, ConfigChannel channel) {
        checkChannelAccess(user, channel);
        Map<String, Object> params = new HashMap<>();
        params.put("ccid", channel.getId());
        params.put("filetype", "symlink");
        params.put(USER_ID, user.getId());
        return doCountFiles(params);
    }

    /**
     * Return the number of Directories in this config-channel
     * @param user user making the request
     * @param channel channel of interest
     * @return number of directories in this channel
     */
    public int getDirCount(User user, ConfigChannel channel)  {
        checkChannelAccess(user, channel);
        Map<String, Object> params = new HashMap<>();
        params.put("ccid", channel.getId());
        params.put("filetype", "directory");
        params.put(USER_ID, user.getId());
        return doCountFiles(params);
    }

    /**
     * Return the number of Files in this config-channel
     * @param user user making the request
     * @param channel channel of interest
     * @return number of files in this channel
     */
    public int getFileCount(User user, ConfigChannel channel)  {
        checkChannelAccess(user, channel);
        Map<String, Object> params = new HashMap<>();
        params.put("ccid", channel.getId());
        params.put("filetype", "file");
        params.put(USER_ID, user.getId());
        return doCountFiles(params);
    }

    /**
     * Return the number of SLS files in this config-channel
     * @param user user making the request
     * @param channel channel of interest
     * @return number of sls files in this channel
     */
    public int getSlsCount(User user, ConfigChannel channel)  {
        checkChannelAccess(user, channel);
        Map<String, Object> params = new HashMap<>();
        params.put("ccid", channel.getId());
        params.put("filetype", "sls");
        params.put(USER_ID, user.getId());
        return doCountFiles(params);
    }

    /**
     * List systems subscribed to this channel, sorted by date-modified (descending)
     * @param user user making the request
     * @param channel channel of interest
     * @return List of Maps with keys ('id','name','modified')
     */
    public DataResult<ConfigSystemDto> getSystemInfo(User user, ConfigChannel channel) {
        checkChannelAccess(user, channel);
        Map<String, Object> params = new HashMap<>();
        params.put("ccid", channel.getId());
        params.put(USER_ID, user.getId());
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "systems_subscribed_by_date");
        return m.execute(params);
    }

    /**
     * List files controlled by this channel, sorted by date-modified (descending)
     * @param user user making the request
     * @param channel channel of interest
     * @return List of ConfigFileDto
     */
    public DataResult<ConfigFileDto> getFileInfo(User user, ConfigChannel channel) {
        checkChannelAccess(user, channel);
        Map<String, Object> params = new HashMap<>();
        params.put("ccid", channel.getId());
        params.put(USER_ID, user.getId());
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "files_by_date");
        return m.execute(params);
    }

    /**
     * Lists the last n most recently modified configuration files visible
     * by a user where n is the results param and user is the user param.
     * @param user The user listing files
     * @param results The number of files to list
     * @return List of recently modified files in DTO format.
     */
    public DataResult<ConfigFileDto> getRecentlyModifiedConfigFiles(User user, Integer results) {
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        params.put("num", results);
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "recent_modified_config_files_for_user");
        return m.execute(params);
    }

    /**
     * Lists the last n most recent config deploy actions visible
     * by a user where n is the results param and user is the user param.
     * @param user The user listing deploy actions
     * @param results The number of actions to list
     * @return List of recently config deploy actions in DTO format.
     */
    public DataResult<ServerActionDto> getRecentConfigDeployActions(User user, Integer results) {
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        params.put("num", results);

        //To reduce the time it takes to sort the set, we only want things that are
        //      less than a week old.  here is the oracle string we conver to:
        //          'YYYY-MM-DD HH24:MI:SS'
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.WEEK_OF_YEAR, -1);
        SimpleDateFormat format = new SimpleDateFormat();
        format.applyPattern("yyyy-MM-dd HH:mm:ss");
        params.put("date", format.format(cal.getTime()));

        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "recent_config_deploy_actions_for_user");
        return m.execute(params);
    }

    /**
     * Return ChannelSummary info - see ChannelOverview
     * @param user user making the request
     * @param channel channel of interest
     * @return summary information for this channel
     */
    public ChannelSummary getChannelSummary(User user, ConfigChannel channel) {
        checkChannelAccess(user, channel);
        ChannelSummary summary = new ChannelSummary();
        summary.setNumSystems(getSystemCount(user, channel));
        summary.setNumDirs(getDirCount(user, channel));
        summary.setNumFiles(getFileCount(user, channel));
        summary.setNumSymlinks(getSymlinkCount(user, channel));
        summary.setNumSls(getSlsCount(user, channel));

        DataResult<ConfigFileDto> dr = getFileInfo(user, channel);
        if (dr != null && !dr.isEmpty()) {
            ConfigFileDto mostRecent = dr.get(0);
            Long revid = mostRecent.getId();
            ConfigRevision rev =
                    ConfigurationManager.getInstance().lookupConfigRevision(user, revid);
            summary.setMostRecentMod(rev);
            String fileDate = StringUtil.categorizeTime(rev.getModified().getTime(),
                    StringUtil.WEEKS_UNITS);
            summary.setRecentFileDate(fileDate);
        }

        DataResult<ConfigSystemDto> infoDr = getSystemInfo(user, channel);
        if (infoDr != null && !infoDr.isEmpty()) {
            ConfigSystemDto mostRecent = infoDr.get(0);
            Long sysid = mostRecent.getId();
            Server sys = ServerFactory.lookupById(sysid);
            summary.setMostRecentSystem(sys);
            Date modDate = mostRecent.getModified();
            String modifiedDate = StringUtil.categorizeTime(modDate.getTime(),
                    StringUtil.WEEKS_UNITS);
            summary.setSystemDate(modifiedDate);
        }
        return summary;
    }

    /**
     * List current files for channel withOUT using a set
     * @param user user making the request
     * @param channel channel of interest
     * @param pc pagination control (if any)
     * @return list of com.redhat.rhn.frontend.dto.ConfigFileDto
     */
    public DataResult<ConfigFileDto> listCurrentFiles(User user,
            ConfigChannel channel, PageControl pc) {
        return listCurrentFiles(user, channel, pc, null, false);
    }

    /**
     * List latest revisions controlled by this channel, sorted by date-modified
     * (descending), optionally constrained by the specified set
     * @param user user making the request
     * @param channel channel of interest
     * @param pc controller/elaborator for the list
     * @param setLabel label of set we care about, or NULL if we don't want to use a set
     * @param includeInitSls should the /init.sls file be included? (for state channels)
     * @return list of com.redhat.rhn.frontend.dto.ConfigFileDto
     */
    public DataResult<ConfigFileDto> listCurrentFiles(User user, ConfigChannel channel, PageControl pc, String setLabel,
            boolean includeInitSls) {
        Map<String, Object> params = new HashMap<>();
        params.put("ccid", channel.getId());
        params.put(USER_ID, user.getId());
        SelectMode m = null;
        if (setLabel != null) {
            m = ModeFactory.getMode(CONFIG_QUERIES, "latest_files_in_namespace_set");
            params.put("set_label", setLabel);
        }
        else {
            params.put("include_init_sls", includeInitSls ? "Y" : "N");
            m = ModeFactory.getMode(CONFIG_QUERIES, "latest_files_in_namespace");
        }
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * List revisions for the given file
     * @param user user making the request
     * @param file config file for which we are listing revisions
     * @param pc controller/elaborator for the list
     * @return List of revisions in dto format.
     */
    public DataResult<ConfigRevisionDto> listRevisionsForFile(User user,
            ConfigFile file, PageControl pc) {
        Map<String, Object> params = new HashMap<>();
        params.put("cfid", file.getId());
        params.put(USER_ID, user.getId());
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "configfile_revisions");
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * List systems subscribed to this channel, sorted by date added (descending)
     * @param user user making the request
     * @param channel channel of interest
     * @param pc controller/elaborator for the list
     * @return List of ConfigSystemDtos
     */
    public DataResult<ConfigSystemDto> listChannelSystems(User user, ConfigChannel channel,
            PageControl pc) {
        Map<String, Object> params = new HashMap<>();
        params.put("ccid", channel.getId());
        params.put(USER_ID, user.getId());
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "systems_subscribed_by_date");
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * List global config channels for a system. Used in the sdc
     * @param user The user requesting for a list of config channels
     * @param server The server subscribed to the config channels
     * @param pc A PageControl for this user
     * @return A list of config channels in DTO format.
     */
    public DataResult<ConfigChannelDto> listChannelsForSystem(User user, Server server, PageControl pc) {
        Map<String, Object> params = new HashMap<>();
        params.put("sid", server.getId());
        params.put(USER_ID, user.getId());
        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("sid", server.getId());
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "config_channels_for_system");
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Returns a map of summary information.
     * The keys of this map are as follows:
     * <ol>
     *  <li>systems - The number of configuration managed
     *                systems viewable by this user.</li>
     *  <li>channels - The number central configuration
     *                 channels viewable by this user.</li>
     *  <li>global_files - The number of centrally-managed
     *                     configuration files viewable by
     *                     this user.</li>
     *  <li>local_files - The number of locally-managed
     *                    configuration files viewable by
     *                    this user.</li>
     *  <li>quota - The amount of unused quota available for
     *              configuration files.  This is returned as
     *              a localized string with units.</li>
     * </ol>
     * @param user The user requesting information
     * @return A map with the keys {systems,channels,
     *         global_files,local_files,quota}
     */
    public Map<String, Long> getOverviewSummary(User user) {
        Map<String, Long> retval = new HashMap<>();
        retval.put("systems", getNumSystemsWithFiles(user));
        retval.put("channels", getNumConfigChannels(user));
        retval.put("global_files", getNumGlobalFiles(user));
        retval.put("local_files", getNumLocalFiles(user));
        return retval;
    }

    /**
     * List systems NOT subscribed to this channel, sorted by name
     * @param user user making the request
     * @param channel channel of interest
     * @param pc controller/elaborator for the list
     * @return List of ConfigSystemDtos
     */
    public DataResult<ConfigSystemDto> listSystemsNotInChannel(User user, ConfigChannel channel,
            PageControl pc) {
        SelectMode mode = null;
        Map<String, Object> params = new HashMap<>();
        params.put("ccid", channel.getId());
        params.put(USER_ID, user.getId());
        if (channel.isStateChannel()) {
            mode = ModeFactory.getMode(CONFIG_QUERIES,
                    "managed_minions_not_in_channel");
        }
        else {
            mode = ModeFactory.getMode(CONFIG_QUERIES,
                    "managed_systems_not_in_channel");
        }
        return makeDataResult(params, new HashMap<>(), pc, mode);
    }

    private Long getNumSystemsWithFiles(User user) {
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "count_managed_servers_for_user");
        DataResult<Map<String, Object>> dr = m.execute(params);
        return (Long)dr.get(0).get("count");
    }

    private Long getNumConfigChannels(User user) {
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "count_config_channels_for_user");
        DataResult<Map<String, Object>> dr = m.execute(params);
        return (Long)dr.get(0).get("count");
    }

    private Long getNumGlobalFiles(User user) {
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "count_global_config_files_for_user");
        DataResult<Map<String, Object>> dr = m.execute(params);
        return (Long)dr.get(0).get("count");
    }

    private Long getNumLocalFiles(User user) {
        Map<String, Object> params = new HashMap<>();
        params.put(USER_ID, user.getId());
        params.put(ORG_ID, user.getOrg().getId());
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "count_local_config_files_for_user");
        DataResult<Map<String, Object>> dr = m.execute(params);
        return (Long)dr.get(0).get("count");
    }

    /**
     * Deletes a config channel. Performs checking to determine whether
     * the user actually can delete the config channel
     * @param user The user requesting to delete the channel
     * @param channel The channel to be deleted.
     * @throws IllegalArgumentException if user is not allowed to delete this
     *         config channel (different org or not config admin).
     */
    public void deleteConfigChannel(User user, ConfigChannel channel) {
        //first make sure that the user has permission to delete this channel
        if (!user.getOrg().equals(channel.getOrg())) {
            throw new IllegalArgumentException("Cannot delete config channel. User" +
                    " and channel are in different orgs");
        }
        // Get associated recurring state actions
        List<RecurringAction> actions = RecurringActionFactory.listActionWithConfChannel(channel);

        //remove the channel
        ConfigChannelSaltManager.getInstance().removeConfigChannelFiles(channel);
        StateFactory.StateRevisionsUsage usage = StateFactory.latestStateRevisionsByConfigChannel(channel);
        removeChannelFromRevision(usage, channel);
        removeChannelFromRecurringActions(actions, channel);
        ConfigurationFactory.removeConfigChannel(channel);
        SaltStateGeneratorService.INSTANCE.regenerateConfigStates(usage);
        SaltStateGeneratorService.INSTANCE.regenerateRecurringStates(actions);
    }

    /**
     * Note: this method cleans up the Hibernated Indexed Collection as the underlying row is deleted via
     * ON DELETE CASCADE and Hibernate would not reflect this change in the Collection otherwise.
     * Remove the config channel from the config channels list referenced in state revision before actually deleting
     * from the database.
     * @param usage StateRevisionsUsage object holding references of revisions where channels is being used
     * @param channel channel to be removed
     */
    private void removeChannelFromRevision(StateFactory.StateRevisionsUsage usage, ConfigChannel channel) {
        usage.getServerStateRevisions().forEach(rev->rev.getConfigChannels().remove(channel));
        usage.getServerGroupStateRevisions().forEach(rev->rev.getConfigChannels().remove(channel));
        usage.getOrgStateRevisions().forEach(rev->rev.getConfigChannels().remove(channel));
    }

    private void removeChannelFromRecurringActions(List<RecurringAction> actions, ConfigChannel channel) {
        actions.forEach(a -> ((RecurringState) a.getRecurringActionType()).getStateConfig()
                .removeIf(c -> c instanceof RecurringConfigChannel reConfigChannel &&
                        reConfigChannel.getConfigChannel().equals(channel)));
    }

    /**
     * Creates a new config revision object.  Looks up the config file from the id given
     * and decides if the user given has access to that file. If both those steps go ok,
     * it creates a new revision and makes it the newest for the file.
     * @param user The user requesting to create the revision
     * @param input The stream containing the revision's content
     * @param cfid The identifier for the parent config file.
     * @param size The size of the given input stream
     * @return The newly created config revision object
     */
    public ConfigRevision createNewRevision(User user, InputStream input,
            Long cfid, Long size) {
        if (input == null) {
            return null;
        }

        //look up the config file
        ConfigFile file = lookupConfigFile(user, cfid);
        if (file == null) {
            //this should never happen because if the file doesn't exist
            //the access should be denied already.
            throw new NullPointerException("ConfigFile is null while attempting" +
                    " to create a new revision.");
        }

        return createNewRevision(user, input, file, size);
    }

    /**
     * Creates a new config revision object.  Looks up the config file from the id given
     * and decides if the user given has access to that file. If both those steps go ok,
     * it creates a new revision and makes it the newest for the file.
     * @param user The user requesting to create the revision
     * @param input The stream containing the revision's content
     * @param file The parent config file.
     * @param size The size of the given input stream
     * @return The newly created config revision object
     */
    public ConfigRevision createNewRevision(User user, InputStream input,
            ConfigFile file, Long size) {
        if (input == null) {
            return null;
        }
        ConfigRevision newRevision = ConfigurationFactory
                .createNewRevisionFromStream(user, input, size, file);
        ConfigChannelSaltManager.getInstance()
                .generateConfigChannelFiles(newRevision.getConfigFile().getConfigChannel());
        return newRevision;
    }
    /**
     * Deletes a config revision. Performs checking to determine whether
     * the user actually can delete the config revision
     * @param user The user requesting to delete the revision
     * @param revision The revision to be deleted.
     * @return whether the file was also deleted.
     * @throws IllegalArgumentException if user is not allowed to delete this
     *         config revision (different org or not config admin).
     */
    public boolean deleteConfigRevision(User user, ConfigRevision revision) {
        //first make sure that the user has permission to delete this revision
        if (!user.getOrg().equals(revision.getConfigFile().getConfigChannel().getOrg())) {
            throw new IllegalArgumentException("Cannot delete config revision. User [" +
                    user.getId() + "] and revision [" +
                    revision.getId() + "] are in different orgs");
        }

        if (!accessToRevision(user.getId(), revision.getId())) {
            throw new IllegalArgumentException("Cannot delete config revision. User [" +
                    user.getId() +
                    "] is not allowed access to revision [" + revision.getId() + "]");
        }

        // Remove the channel
        boolean isFileDeleted = false;
        if (revision.isInitSls()) {
            try {
                ConfigurationFactory.safeRemoveConfigRevision(revision,
                        user.getOrg().getId());
            }
            catch (ConfigFileSafeDeleteException e) {
                throw new IllegalArgumentException("Cannot delete the only revision for the init.sls file.", e);
            }
        }
        else {
            isFileDeleted = ConfigurationFactory.removeConfigRevision(revision, user.getOrg().getId());
        }

        ConfigChannelSaltManager.getInstance()
                .generateConfigChannelFiles(revision.getConfigFile().getConfigChannel());
        return isFileDeleted;
    }

    /**
     * Deletes a config file. Performs checking to determine whether
     * the user actually can delete the config file
     * @param user The user requesting to delete the file
     * @param file The file to be deleted.
     * @throws IllegalArgumentException if user is not allowed to delete this
     *         config file (different org or not config admin).
     */
    public void deleteConfigFile(User user, ConfigFile file) {
        //first make sure that the user has permission to delete this file
        if (!user.getOrg().equals(file.getConfigChannel().getOrg())) {
            throw new IllegalArgumentException("Cannot delete config file. User" +
                    " and file are in different orgs");
        }
        if (!accessToFile(user.getId(), file.getId())) {
            throw new IllegalArgumentException(
                    "User [" + user.getId() +
                    "] does not have access to file [" + file.getId() + "].");
        }
        if (ConfigChannelType.state().equals(file.getConfigChannel().getConfigChannelType()) &&
                file.getLatestConfigRevision().isInitSls()) {
            throw new IllegalArgumentException("Cannot delete the init.sls file.");
        }

        //remove the file
        ConfigurationFactory.removeConfigFile(file);
        // we have removed a file using a Mode, let's clear the hibernate cache so that
        // the ConfigChannelSaltManager sees the up-to-date state
        HibernateFactory.getSession().clear();
        ConfigChannelSaltManager.getInstance().generateConfigChannelFiles(
                HibernateFactory.reload(file.getConfigChannel()));
    }

    /**
     * Copies a config file. Performs checking to determine whether
     * the user actually can copy the config file.
     * Only copies the revision of the file given. Puts the revision into a config
     * file with the same deploy path in the new channel, or creates a config file if
     * a candidate file does not exist.
     * @param revision The revision of the file to be copied.
     * @param channel The channel to which to copy.
     * @param user The user requesting to copy the file
     * @throws IllegalArgumentException if user is not allowed to copy this
     *         config file (different org or not config admin).
     */
    public void copyConfigFile(ConfigRevision revision, ConfigChannel channel,
            User user) {
        //first make sure that the user has permissions to the revision and channel
        if (!user.getOrg().equals(revision.getConfigFile().getConfigChannel().getOrg()) ||
                !user.getOrg().equals(channel.getOrg())) {
            throw new IllegalArgumentException("Cannot copy config file. User," +
                    " revision, and channel are in different orgs");
        }
        checkChannelAccess(user, channel);
        if (revision.isInitSls() && !channel.isStateChannel()) {
            throw new IllegalArgumentException("Can only copy the init.sls file to a state channel.");
        }

        //copy the file
        ConfigurationFactory.copyRevisionToChannel(user, revision, channel);
        // here - re-create the channel on disk
        ConfigChannelSaltManager.getInstance().generateConfigChannelFiles(channel);
    }


    /**
     * For a given filename and server, find all the successful deploys of a file with that
     * name
     * @param usr User making the request
     * @param cfn name of interest
     * @param srv server of interest
     * @return list of LastDeployDtos
     */
    public DataResult<LastDeployDto> getSuccesfulDeploysTo(User usr, ConfigFileName cfn, Server srv) {
        // Validate params
        if (usr == null || cfn == null || srv == null) {
            throw new IllegalArgumentException("User, name, and server cannot be null.");
        }

        //first make sure that the user has permissions to the system
        if (!usr.getOrg().equals(srv.getOrg())) {
            throw new IllegalArgumentException("Cannot examine deploys; " +
                    "user and system are in different orgs.");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("cfnid", cfn.getId());
        params.put("sid", srv.getId());
        params.put(USER_ID, usr.getId());
        SelectMode m = ModeFactory
                .getMode(CONFIG_QUERIES, "successful_deploys_for");
        return m.execute(params);
    }

    /**
     * For a specified channel, return info about all config-files that the
     * user has access to that are NOT already in that channel
     * @param usr User making the request
     * @param cc ConfigChannel of interest
     * @param pc A page control for this user.
     * @return DataResult; entities are cfid, path, ccid, name, and modified
     */
    public DataResult<ConfigFileDto> listFilesNotInChannel(User usr, ConfigChannel cc, PageControl pc) {
        // Validate params
        if (usr == null || cc == null) {
            throw new IllegalArgumentException("User and channel cannot be null.");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("ccid", cc.getId());
        params.put(USER_ID, usr.getId());
        params.put("orgid", usr.getOrg().getId());
        SelectMode m = ModeFactory
                .getMode(CONFIG_QUERIES, "config_files_not_in_channel");
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * For a specified ConfigChannel, return overview info for the systems that are
     * subscribed to that channel.
     * @param usr User making the request
     * @param cc ConfigChannel of interest
     * @param pc PageControl (if we're paginating)
     * @return DataResult of ConfigSystemDtos, with id,name,outrankedCount and
     * overriddenCount filled in
     */
    public DataResult<ConfigSystemDto> listSystemInfoForChannel(User usr, ConfigChannel cc, PageControl pc) {
        return listSystemInfoForChannel(usr, cc, pc, false);
    }

    /**
     * For a specified ConfigChannel, return overview info for the systems that are
     * subscribed to that channel.
     * @param usr User making the request
     * @param cc ConfigChannel of interest
     * @param pc PageControl (if we're paginating)
     * @param useSet true if we should limit by set_label, false if we want ALL systems
     * in the channel
     * @return DataResult of ConfigSystemDtos, with id,name,outrankedCount and
     * overriddenCount filled in
     */
    public DataResult<ConfigSystemDto> listSystemInfoForChannel(
            User usr, ConfigChannel cc, PageControl pc, boolean useSet) {
        // Validate params
        if (usr == null || cc == null) {
            throw new IllegalArgumentException("User and channel cannot be null.");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("ccid", cc.getId());
        params.put(USER_ID, usr.getId());

        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("ccid", cc.getId());
        SelectMode m = null;

        if (useSet) {
            params.put("set_label", RhnSetDecl.CONFIG_CHANNEL_DEPLOY_SYSTEMS.getLabel());
            m = ModeFactory.getMode(CONFIG_QUERIES, "systems_in_channel_info_set");
        }
        else {
            m = ModeFactory.getMode(CONFIG_QUERIES, "systems_in_channel_info");
        }
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Provides a list of 'Unique' paths (ConfigFileNameDto's)
     * for a given server and channel type. The returned list
     * takes care of the channel priority ordering and stuff like that..
     * This is mainly used in the View/Modify files page
     * of the SDC.
     * @param server the server who's paths are to be retrieved
     * @param user the user needed for permission checking
     * @param type config channel type that holds the files
     * @return a list of unique'ly named paths sorted by the name of
     *          type com.redhat.rhn.frontend.dto.ConfigFileNameDto
     */
    public List<ConfigFileNameDto> listManagedPathsFor(Server server, User user, ConfigChannelType type) {
        Map<String, Object> params = new HashMap<>();
        params.put("sid", server.getId());
        params.put(USER_ID, user.getId());
        params.put("channel_type", type.getLabel());
        String modeQuery = "central_managed_files_for_sdc";
        if (ConfigChannelType.sandbox().equals(type)) {
            modeQuery = "sandbox_managed_files_for_sdc";
        }
        else if (ConfigChannelType.local().equals(type)) {
            modeQuery = "local_managed_files_for_sdc";
        }
        SelectMode m = ModeFactory
                .getMode(CONFIG_QUERIES, modeQuery);

        Map<String, Object> elabParams = new HashMap<>();
        elabParams.put("sid", server.getId());
        elabParams.put("channel_type", type.getLabel());
        DataResult<ConfigFileNameDto> result = m.execute(params);
        result.elaborate(elabParams);
        return result;
    }

    /**
     *  Returns the number of files, and directories
     *  that were on a applied to server by a given config action
     *  This is method is mainly used to show the number
     *  of files and directories that were deployed/diff'd
     *  Note this method doesnot check whether the Action is
     *  visible to the user. It is assumed that whomever is
     *  calling this has already ensured that the Action
     *  is visible to the user.
     * @param server The server for whom the count of files
     *              is desired.
     * @param action the action for whom the number of files
     *              and dirs are desired.
     * @return ConfigFileCount object holding the files and dirs
     */
    public ConfigFileCount countAllActionPaths(Server server,
            Action action) {
        return countActionPaths(server,
                action,
                "count_paths_in_action");
    }

    /**
     *  Returns the number of files, and directories
     *  that were SUCCESSFULLY applied to server by a given config action
     *  This is method is mainly used to show the number
     *  of files and directories that were scheduled for comparison
     *  Returns the number of files
     *  that were selected successfully for comparison
     *  in a config DIFF action.
     *  In other words this method subtracts the missing files
     *  from the total for a given diff action..
     *  Note this method doesnot check whether the Action is
     *  visible to the user. It is assumed that whomever is
     *  calling this has already ensured that the Action
     *  is visible to the user.
     * @param server The server for whom the count of files
     *              is desired.
     * @param action the action for whom the number of files
     *              and dirs are desired.
     * @return ConfigFileCount object holding the number of
     *                          NON Missing files/dirs
     *                          that were selected for comparison
     */
    public ConfigFileCount countSuccessfulCompares(Server server,
            Action action) {
        return countActionPaths(server,
                action,
                "count_successfully_compared_paths");
    }


    /**
     *  Returns the number of files on the server that differed
     *  in content from the files in RHN - Managed
     *  Note this method doesnot check whether the Action is
     *  visible to the user. It is assumed that whomever is
     *  calling this has already ensured that the Action
     *  is visible to the user.
     * @param server The server for whom the count of files
     *              is desired.
     * @param action the action for whom the number of files
     *              and dirs are desired.
     * @return ConfigFileCount object holding the files and dirs
     */
    public ConfigFileCount countDifferingPaths(Server server,
            Action action) {
        return countActionPaths(server,
                action,
                "count_differing_paths");
    }
    private ConfigFileCount countActionPaths(Server server,
            Action action, String query) {
        Map<String, Object> params = new HashMap<>();
        params.put("sid", server.getId());
        params.put("aid", action.getId());
        return processCountedFilePathQueries(query, params);
    }

    /**
     *  Returns the number of files, and directories
     *  that are managed in the local override channel or Sandbox channel
     *  of a given server..
     * @param server The server for whom the count of files
     *              is desired.
     * @param user The user required for permission purposes
     * @param cct The local channel type of the to look at (local/sandbox)
     * @return ConfigFileCount object holding the files and dirs
     *
     */
    public ConfigFileCount countLocallyManagedPaths(Server server,
            User user,
            ConfigChannelType cct) {

        boolean isLocal = ConfigChannelType.local().equals(cct) ||
                ConfigChannelType.sandbox().equals(cct);
        assert isLocal : "Passing in a NON-LOCAL  channel type";
        Map<String, Object> params = new HashMap<>();
        params.put("sid", server.getId());
        params.put(USER_ID, user.getId());
        params.put("cct_label", cct.getLabel());
        return processCountedFilePathQueries("count_locally_managed_file_paths",
                params);
    }

    private ConfigFileCount processCountedFilePathQueries(String query, Map<String, ?> params) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, query);
        List<Map<String, Object>> results = m.execute(params);
        long files = 0, slsFiles = 0, dirs = 0, symlinks = 0;

        for (Map<String, Object> map: results) {
            Long count = (Long) map.get("count");
            String fileType = (String) map.get("file_type");

            if (ConfigFileType.file().getLabel().equals(fileType)) {
                files = count;
            }
            else if (ConfigFileType.sls().getLabel().equals(fileType)) {
                slsFiles = count;
            }
            else if (ConfigFileType.symlink().getLabel().equals(fileType)) {
                symlinks = count;
            }
            else {
                dirs = count;
            }
        }

        return ConfigFileCount.create(files, slsFiles,  dirs, symlinks);
    }

    /**
     *  Returns the sum of files, and directories
     *  that are present in the all the centrally managed channels
     *  in a given server.
     *  This method strips out all the duplicate file paths before counting,
     *  and accounts for channel priorities..
     *  For example if a path /tmp/foo is a file in channel A and a directory
     *  in channel B and our Server subscribes to both channels,
     *  Then this method would take into account the priority of the channels
     *  before incrementing File count or Directory count.
     *
     * @param server The server for whom the count of files
     *              is desired.
     * @param user The user required for permission purposes
     * @return  a ConfigFileCount object holding the files and dirs
     */
    public ConfigFileCount countCentrallyManagedPaths(Server server, User user) {
        return countManagedPaths(server, user, "centrally_managed_file_paths");
    }

    /**
     *  Returns the sum of all the 'Deployable' files, and directories
     *  that are present in the all the centrally managed channels
     *  in a given server. This is similar to 'countCentrallyManagedPaths'
     *  except that it also takes into account the file/directory path intersections
     *  between the local override channel and all the centrally managed channels
     *  (basically subtracting them from the central list).
     *   In clearer terms, for a system A
     *  num_of_centrally_deployable_files(A) =  countCentrallyManagedPaths (A)
     *                                     - count(
     *                         centrallyManagedPaths(A) ^ locallyManagedPaths(A)
     *                                              )
     *
     * @param server The server for whom the count of files
     *              is desired.
     * @param user The user required for permission purposes
     * @return ConfigFileCount object holding the files and dirs
     *
     */
    public ConfigFileCount countCentrallyDeployablePaths(Server server, User user) {
        return countManagedPaths(server, user, "centrally_deployable_file_paths");
    }

    /**
     * Returns the count of files and directories after execting a mode query
     * basically used by  countCentrallyManagedPaths & countCentrallyDeployablePaths.
     * It expects the result set to be a list of path, and file_type
     * It partitions this list removes, duplicates and does extra
     * processing.
     * @param server The server for whom the count of files
     *              is desired.
     * @param user The user required for permission purposes
     * @param mode
     * @return a ConfigFileCount object holding the files and dirs
     *
     */
    private ConfigFileCount countManagedPaths(Server server, User user, String mode) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, mode);
        Map<String, Object> params = new HashMap<>();
        params.put("sid", server.getId());
        params.put(USER_ID, user.getId());
        List<Map<String, Object>> pathList = m.execute(params);
        Set<String> files = new HashSet<>();
        Set<String> slsfiles = new HashSet<>();
        Set<String> dirs = new HashSet<>();
        Set<String> symlinks = new HashSet<>();
        for (Map<String, Object> map: pathList) {
            String path = (String) map.get("path");
            String fileType = (String) map.get("file_type");
            if (ConfigFileType.file().getLabel().equals(fileType) && !dirs.contains(path) && !symlinks.contains(path)) {
                files.add(path);
            }
            if (ConfigFileType.sls().getLabel().equals(fileType) && !dirs.contains(path) && !symlinks.contains(path)) {
                slsfiles.add(path);
            }
            else if (ConfigFileType.symlink().getLabel().equals(fileType) &&
                    !dirs.contains(path) && !files.contains(path)) {
                symlinks.add(path);
            }
            else if (!files.contains(path) && !symlinks.contains(path)) {
                dirs.add(path);
            }
        }
        return ConfigFileCount.create(files.size(), slsfiles.size(), dirs.size(), symlinks.size());
    }

    /**
     * Looks up a config channel, if the given user has access to it.
     * @param user The user requesting to lookup a config channel.
     * @param id The identifier for the config channel
     * @return The sought for config channel.
     */
    public ConfigChannel lookupConfigChannel(User user, Long id) {
        if (!accessToChannel(user.getId(), id)) {
            LocalizationService ls = LocalizationService.getInstance();
           throw new LookupException("Could not find config channel with id=" + id,
                   ls.getMessage("lookup.configchan.title"),
                   ls.getMessage("lookup.configchan.reason1"),
                   ls.getMessage("lookup.configchan.reason2"));
        }
        return ConfigurationFactory.lookupConfigChannelById(id);
    }

    /**
     * Looks up a  global('normal', 'state') config channel, if the given user has access to it.
     * @param user The user requesting to lookup a config channel.
     * @param label The label for the ConfigChannel
     * @return The sought for config channel.
     */
    public ConfigChannel lookupGlobalConfigChannel(User user, String label) {
        Optional<ConfigChannel> configChannel =
                ConfigurationFactory.lookupGlobalConfigChannelByLabel(label, user.getOrg());
        return configChannel.filter(cc->
                accessToChannel(user.getId(), cc.getId())
        ).orElse(null);
    }

    /**
     * Looks up a config file, if the given user has access to it.
     * @param user The user requesting to lookup a config file.
     * @param id The identifier for the config file.
     * @return The sought for config file.
     */
    public ConfigFile lookupConfigFile(User user, Long id) {
        log.debug("lookupConfigFile: {}", id);
        if (!accessToFile(user.getId(), id)) {
            LocalizationService ls = LocalizationService.getInstance();
            throw new LookupException("Could not find config file with id=" + id,
                    ls.getMessage("lookup.configfile.title"),
                    ls.getMessage("lookup.configfile.reason1"),
                    ls.getMessage("lookup.configfile.reason2"));
        }
        return ConfigurationFactory.lookupConfigFileById(id);
    }

    /**
     * Look up a config-file with a specified name in a specified cfg-channel.
     * If the specified path is not yet in the system, it will be created as a
     * ConfigFileName (under the assumption that if we're asking this,
     * chances are good we're going to want to create a ConfigFile with this
     * path Real Soon Now...)
     *
     * @param user User making the request
     * @param ccid ID of tyhe cohnfig-channel of interest
     * @param path file-path of interest
     * @return ConfigFile if found, or null if it doesn't exist or if the user doesn't
     * have sufficient access
     */
    public ConfigFile lookupConfigFile(User user, Long ccid, String path) {
        if (!accessToChannel(user.getId(), ccid)) {
            LocalizationService ls = LocalizationService.getInstance();
            throw new LookupException("Could not find config file with id=" + ccid,
                    ls.getMessage("lookup.configfile.title"),
                    ls.getMessage("lookup.configfile.reason1"),
                    ls.getMessage("lookup.configfile.reason2"));
        }
        ConfigFileName cfn = ConfigurationFactory.lookupOrInsertConfigFileName(path);
        return ConfigurationFactory.lookupConfigFileByChannelAndName(ccid, cfn.getId());
    }

    /**
     * Looks up a config revision, if the given user has access to it.
     * @param user The user requesting to lookup a config revision.
     * @param id The identifier for the config revision.
     * @return The sought for config revision.
     */
    public ConfigRevision lookupConfigRevision(User user, Long id) {
        if (!accessToRevision(user.getId(), id)) {
            LocalizationService ls = LocalizationService.getInstance();
            throw new LookupException("Could not find config revision with id=" + id,
                    ls.getMessage("lookup.configrev.title"),
                    ls.getMessage("lookup.configrev.reason1"),
                    ls.getMessage("lookup.configrev.reason2"));
        }
        return ConfigurationFactory.lookupConfigRevisionById(id);
    }

    /**
     * For a given configuration file, looks up a config revision id
     * @param user The user requesting to lookup a config revision.
     * @param cf The ConfigFile that the revision applies to
     * @param revId The ConfigFile revision id.
     * @return The sought for config revision.
     */
    public ConfigRevision lookupConfigRevisionByRevId(User user, ConfigFile cf,
            Long revId) {
        ConfigRevision cr = ConfigurationFactory.lookupConfigRevisionByRevId(cf, revId);

        if (cr == null) {
            throw new LookupException("Could not find config revision with revision id=" +
                    revId);
        }

        if (!accessToRevision(user.getId(), cr.getId())) {
            LocalizationService ls = LocalizationService.getInstance();
            throw new LookupException("Could not find config revision with revision id=" + revId,
                    ls.getMessage("lookup.configrev.title"),
                    ls.getMessage("lookup.configrev.reason1"),
                    ls.getMessage("lookup.configrev.reason2"));
        }

        return cr;
    }

    /**
     * For a given configuration file, return list of config revisions
     * @param cf ConfigFile to lookup the revision for.
     * @return List of config file revisions.
     */
    public List<ConfigRevision> lookupConfigRevisions(ConfigFile cf) {
        return ConfigurationFactory.lookupConfigRevisions(cf);
    }

    /**
     * @param uid The user id
     * @param ccid The config channel id
     * @return whether the user with the given id can view the
     *         config channel with the given id.
     */
    public boolean accessToChannel(Long uid, Long ccid) {
        return accessToObject(uid, "channel_id", ccid, "user_channel_access");
    }

    private boolean accessToFile(Long uid, Long cfid) {
        return accessToObject(uid, "file_id", cfid, "user_file_access");
    }

    private boolean accessToRevision(Long uid, Long crid) {
        return accessToObject(uid, "revision_id", crid, "user_revision_access");
    }

    private boolean accessToObject(Long uid, String name, Long oid, String mode) {
        log.debug("accessToObject :: uid: {} name: {} oid: {} mode: {}", uid, name, oid, mode);
        CallableMode m = ModeFactory.getCallableMode(CONFIG_QUERIES, mode);
        Map<String, Object> inParams = new HashMap<>();
        inParams.put(USER_ID, uid);
        inParams.put(name, oid);

        Map<String, Integer> outParams = new HashMap<>();
        outParams.put("access", Types.NUMERIC);

        Map<String, Object> result = m.execute(inParams, outParams);
        int access = ((Long)result.get("access")).intValue();
        return (access == 1);
    }

    /**
     * Returns the config revision id for a config file with the given config
     * file name id.  The config revision is one is the highest priority config channel
     * for the server with the given id.
     * @param cfnid The config file name id
     * @param sid The server id
     * @return The deployable config revision for the given server with the given name.
     */
    public Long getDeployableRevisionForFileName(Long cfnid, Long sid) {
        if (cfnid == null || sid == null) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("cfnid", cfnid);
        params.put("sid", sid);
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "deployable_revision_for_system");
        DataResult<Map<String, Object>> dr = m.execute(params);
        if (dr.isEmpty()) {
            return null;
        }
        Object id = dr.get(0).get("id");
        if (id == null) {
            return null;
        }
        return (Long)id;
    }

    /**
     * Enable the set of systems given for configuration management.
     * @param set The set that contains systems selected for enablement
     * @param user The user requesting to enable systems
     * @param earliest The earliest time package actions will be scheduled.
     * @throws TaskomaticApiException if there was a Taskomatic error
     * (typically: Taskomatic is down)
     */
    public void enableSystems(RhnSetDecl set, User user, Date earliest)
        throws TaskomaticApiException {
        EnableConfigHelper helper = new EnableConfigHelper(user);
        helper.enableSystems(set.getLabel(), earliest);
    }

    /**
     * List the info for the systems subscribed to the specified channel,
     * for which we might want to schedule a deploy of the specified file,
     * without being constrained by a selected set of systems
     * @param usr logged-in user
     * @param cc cfg-channel of interest
     * @param cf cfg=file of interest
     * @param pc paging control for UI control
     * @return list of ConfigGlobalDeployDtos
     */
    public DataResult<ConfigGlobalDeployDto> listGlobalFileDeployInfo(
            User usr, ConfigChannel cc,
            ConfigFile cf, PageControl pc) {
        return listGlobalFileDeployInfo(usr, cc, cf, pc, null);
    }

    /**
     * List the info for the systems subscribed to the specified channel,
     * for which we might want to schedule a deploy of the specified file,
     * optionally constrained by a selected set of systems
     * @param usr User making the request
     * @param cc Config Channel File is in
     * @param cf ConfigFile of interest
     * @param pc page-control for UI paging
     * @param setLabel label of limiting set, or NULL if not set-limited
     * @return DataResult of ConfigGlobalDeployDtos
     */
    public DataResult<ConfigGlobalDeployDto> listGlobalFileDeployInfo(
            User usr, ConfigChannel cc,
            ConfigFile cf, PageControl pc,
            String setLabel) {
        // Validate params
        if (usr == null || cc == null || cf == null) {
            throw new IllegalArgumentException(
                    "User, channel, and config-file cannot be null.");
        }
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> elabParams = new HashMap<>();

        SelectMode m = null;
        if (setLabel != null) {
            m = ModeFactory.getMode(CONFIG_QUERIES, "global_file_deploy_set_info");
            params.put(USER_ID, usr.getId());
            params.put("ccid", cc.getId());
            params.put("set_label", setLabel);
            elabParams.put("ccid", cc.getId());
            elabParams.put("cfnid", cf.getConfigFileName().getId());
        }
        else {
            m = ModeFactory.getMode(CONFIG_QUERIES, "global_file_deploy_info");
            params.put(USER_ID, usr.getId());
            params.put("ccid", cc.getId());
            params.put("cfnid", cf.getConfigFileName().getId());
        }

        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Schedules deploys of all the configuration files or dirs
     * associated to a list of servers
     *
     * @param user User needed for authentication purposes..
     * @param servers The list of servers, to whom the deploy action
     *                  needs to be scheduled
     * @param datePicked date to deploy or null for the earliest date
     * @throws TaskomaticApiException if there was a Taskomatic error
     * (typically: Taskomatic is down)
     */
    public void deployConfiguration(User user,
            Collection<Server> servers,
            Date datePicked) throws TaskomaticApiException {
        deployConfiguration(user, servers, null, datePicked);
    }


    /**
     * Schedules deploys of all the configuration files or dirs
     * associated to a list of servers
     *
     * @param user User needed for authentication purposes..
     * @param servers The list of servers, to whom the deploy action
     *                  needs to be scheduled
     * @param channel ConfigChannel to deploy files from
     * @param datePicked date to deploy or null for the earliest date
     * @throws TaskomaticApiException if there was a Taskomatic error
     * (typically: Taskomatic is down)
     */
    public void deployConfiguration(User user,
            Collection<Server> servers,
            ConfigChannel channel,
            Date datePicked) throws TaskomaticApiException {
        if (datePicked == null) {
            datePicked = new Date();
        }
        for (Server server : servers) {
            ensureConfigManageable(server);

            List<ConfigFileNameDto> names;
            if (channel == null) {
                names = listFileNamesForSystem(user, server, null);
            }
            else {
                names = listFileNamesForSystemChannel(user, server, channel, null);
            }
            if (names.isEmpty()) {
                log.warn("No files exists for {} --skipping", server.getHostname());
                continue;
            }
            Set<Server> system = new HashSet<>();
            system.add(server);
            Set<Long> revs = new HashSet<>();
            for (ConfigFileNameDto dto : names) {
                revs.add(getDeployableRevisionForFileName(dto.getId(),
                        server.getId()));
            }

            Action act = ActionManager.createConfigActionForServers(
                    user, revs, system,
                    ActionFactory.TYPE_CONFIGFILES_DEPLOY,
                    datePicked);
            ActionFactory.save(act);
        }
    }

    /**
     * Deploy revisions to systems.
     * For each system, make sure the specified revisions are all the top-priority
     * files - if they're not, flag an error and continue.
     * @param usr User requesting the deploy
     * @param fileIds Revisions to be deployed
     * @param systemIds Systems to deploy to
     * @param datePicked Date to schedule the deploy for
     * @return map describing "success"|"override"|"failure"
     */
    public Map<String, Long> deployFiles(User usr, Set<Long> fileIds, Set<Long> systemIds, Date datePicked) {
        return deployFiles(usr, fileIds, systemIds, datePicked, null);
    }

    /**
     * Deploy revisions to systems, optionally in an Action Chain.
     * For each system, make sure the specified revisions are all the top-priority
     * files - if they're not, flag an error and continue.
     * @param usr User requesting the deploy
     * @param fileIds Revisions to be deployed
     * @param systemIds Systems to deploy to
     * @param datePicked Date to schedule the deploy for
     * @param actionChain the action chain to add the action to or null
     * @return Map describing "success"|"override"|"failure"
     */
    public Map<String, Long> deployFiles(User usr, Set<Long> fileIds, Set<Long> systemIds, Date datePicked,
        ActionChain actionChain) {

        int revOverridden = 0;
        int revSucceeded = 0;

        // First, map revid to cfnid once, so we don't have to do it per system
        Map<Long, Long> nameMap = mapFileToName(fileIds);
        Map<Long, Long> fileMap = mapFileToRevId(fileIds);

        List<Long> servers = new LinkedList<>(systemIds);

        Map<Long, Collection<Long>> serverConfigMap =
                new HashMap<>();
        // For all systems
        for (Long serverId : servers) {
            Set<Long> revs = new HashSet<>();
            // For each revision....
            for (Long file : fileIds) {
                Long rev = fileMap.get(file);
                Long cfnid = nameMap.get(file);
                Long deployableRev = getDeployableRevisionForFileName(
                        cfnid, serverId);
                revs.add(deployableRev);
                if (rev.equals(deployableRev)) {
                    revSucceeded++;
                }
                else {
                    revOverridden++;
                }
            }
            serverConfigMap.put(serverId, revs);
        }

        SsmConfigFilesEvent event =
                new SsmConfigFilesEvent(usr.getId(), serverConfigMap, servers,
                        ActionFactory.TYPE_CONFIGFILES_DEPLOY, datePicked, actionChain);
        MessageQueue.publish(event);

        Map<String, Long> m = new HashMap<>();

        if (revSucceeded > 0) {
            m.put("success", (long) revSucceeded);
        }
        if (revOverridden > 0) {
            m.put("override", (long) revOverridden);
        }
        return m;
    }

    /**
     * From file id, get file.fileName and map to file-id
     * @param fileIds set of file-ids of interest
     * @return Map of file-id to cfn-id
     */
    private Map<Long, Long> mapFileToName(Set<Long> fileIds) {
        Map<Long, Long> m = new HashMap<>();
        for (Long id : fileIds) {
            ConfigFile cf = ConfigurationFactory.lookupConfigFileById(id);
            if (cf != null) {
                m.put(id, cf.getConfigFileName().getId());
            }
        }
        return m;
    }

    /**
     * From file id, get file.latest-rev and map to file-id
     * @param fileIds set of file-ids of interest
     * @return Map of file-id to cr-id
     */
    private Map<Long, Long> mapFileToRevId(Set<Long> fileIds) {
        Map<Long, Long> m = new HashMap<>();
        for (Long id : fileIds) {
            ConfigFile cf = ConfigurationFactory.lookupConfigFileById(id);
            if (cf != null) {
                m.put(id, cf.getLatestConfigRevision().getId());
            }
        }
        return m;
    }

    /**
     * Method to ensure  config management features are available for a given system
     *   are available..
     * @param server the server to check.
     */
    public void ensureConfigManageable(Server server) {
        if (server == null) {
            throw new LookupException("Server doesn't exist");
        }

        if (!SystemManager.serverHasFeature(server.getId(),
                FEATURE_CONFIG)) {
            String msg = "Config feature needs to be enabled on the server" +
                    " for handling Config Management. The provided server [%s]" +
                    " does not have have this enabled. Add provisioning" +
                    " capabilities to the system to enable this..";
            throw new PermissionException(String.format(msg, server));
        }
    }


    /**
     * Returns the server id associated to a local/sandbox channel
     * @param cc the local or sandbox channel
     * @param user the logged in user.
     * @return the server id associated to a local/sandbox channel
     */
    public Long getServerIdFor(ConfigChannel cc, User user) {
        if (cc.isLocalChannel() || cc.isSandboxChannel()) {
            Long sid = null;
            DataResult<ConfigSystemDto> dr =  listChannelSystems(user, cc, null);
            if (dr == null || dr.isEmpty()) {
                return null;
            }
            ConfigSystemDto csd = dr.get(0);
            sid = csd.getId();
            return sid;
        }
        return null;
    }

    /**
     * Returns true if there already exists a config channel that is uniquely determined
     * by given label, channel type and organization.
     *
     * Note: Handling of the channels of 'state' and 'normal' types is stricter: if there is
     * already a channel of 'state' type, the method will return true also for the 'normal'
     * channel type (with the same name and org) and vice versa.
     *
     * @param label Label of the config channel
     * @param cct the contig channel type
     * @param org the org of the current user
     * @return true if there already exists such a channel/false otherwise.
     */
    public static boolean conflictingChannelExists(String label, ConfigChannelType cct, Org org) {
        if (cct.getLabel().equals(ConfigChannelType.STATE) ||  cct.getLabel().equals(ConfigChannelType.NORMAL)) {
            // if global channel, we want to be a bit stricter
            return channelExists(label, ConfigChannelType.state(), org) ||
                    channelExists(label, ConfigChannelType.normal(), org);
        }
        else {
            return channelExists(label, cct, org);
        }
    }

    /**
     * Returns true if there already exists
     * a config channel with the same label, cc type and org.
     * @param label Label of the config channel
     * @param cct the contig channel type
     * @param org the org of the current user
     * @return true if there already exists such a channel/false otherwise.
     */
    private static boolean channelExists(String label, ConfigChannelType cct, Org org) {
        Map<String, Object> params = new HashMap<>();
        params.put("cc_label", label);
        params.put("cct_label", cct.getLabel());
        params.put(ORG_ID, org.getId());
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "lookup_id_by_label_org_channel_type");
        DataResult<Map<String, Object>> dr = m.execute(params);
        return !dr.isEmpty();
    }

    /**
     * @param sid server ID
     * @param ssid snapshot ID
     * @param pc page control
     * @return Difference of config channel subscription between current state and snapshot
     */
    public static DataResult<Row> systemSnapshotConfigChannels(Long sid, Long ssid,
                                                               PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES,
                "snapshot_configchannel_diff");
        Map<String, Object> params = new HashMap<>();
        params.put("sid", sid);
        params.put("ss_id", ssid);
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    /**
     * @param ssid snapshot ID
     * @param pc page control
     * @return List of config files which will be redeployed during rollback
     */
    public static DataResult<Row> systemSnapshotConfigFiles(Long ssid, PageControl pc) {
        SelectMode m = ModeFactory.getMode(CONFIG_QUERIES, "configfiles_for_snapshot");
        Map<String, Object> params = new HashMap<>();
        params.put("ss_id", ssid);
        return makeDataResult(params, new HashMap<>(), pc, m);
    }

    private void checkChannelAccess(User user, ConfigChannel channel) throws IllegalArgumentException {
        if (!accessToChannel(user.getId(), channel.getId())) {
            throw new IllegalArgumentException(
                    String.format("User [%d] has no access to channel [%d]", user.getId(), channel.getId()));
        }
    }

}
