/**
 * Copyright (c) 2013, Redsolution LTD. All rights reserved.
 *
 * This file is part of Xabber project; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License, Version 3.
 *
 * Xabber is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.xabber.android.data.extension.capability;

import android.database.Cursor;

import com.xabber.android.data.Application;
import com.xabber.android.data.LogManager;
import com.xabber.android.data.NetworkException;
import com.xabber.android.data.OnLoadListener;
import com.xabber.android.data.account.AccountItem;
import com.xabber.android.data.account.listeners.OnAccountRemovedListener;
import com.xabber.android.data.connection.ConnectionItem;
import com.xabber.android.data.connection.ConnectionManager;
import com.xabber.android.data.connection.listeners.OnAuthorizedListener;
import com.xabber.android.data.connection.listeners.OnDisconnectListener;
import com.xabber.android.data.connection.listeners.OnPacketListener;
import com.xabber.android.data.database.sqlite.CapabilitiesTable;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.BaseEntity;
import com.xabber.android.data.entity.NestedMap;
import com.xabber.android.data.entity.UserJid;
import com.xabber.android.data.roster.RosterManager;

import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.IQ.Type;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smackx.caps.packet.CapsExtension;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo;
import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.jxmpp.jid.FullJid;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.stringprep.XmppStringprepException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Provide information about entity capabilities.
 *
 * @author alexander.ivanov
 */
public class CapabilitiesManager implements OnAuthorizedListener,
        OnDisconnectListener, OnAccountRemovedListener, OnPacketListener,
        OnLoadListener {

    private static final String FORM_TYPE = "FORM_TYPE";

    public static final ClientInfo INVALID_CLIENT_INFO = new ClientInfo(null,
            null, null, new ArrayList<String>());

    private final static CapabilitiesManager instance;

    static {
        instance = new CapabilitiesManager();
        Application.getInstance().addManager(instance);
    }

    public static CapabilitiesManager getInstance() {
        return instance;
    }

    /**
     * List of sent requests.
     */
    private final Collection<DiscoverInfoRequest> requests;

    /**
     * Capability information for full jid in account.
     */
    private final NestedMap<Capability> userCapabilities;

    /**
     * Capabilities information with associated discovery information.
     */
    private final Map<Capability, ClientInfo> clientInformations;

    private CapabilitiesManager() {
        requests = new ArrayList<>();
        userCapabilities = new NestedMap<>();
        clientInformations = new HashMap<>();
    }

    @Override
    public void onLoad() {
        Cursor cursor = CapabilitiesTable.getInstance().list();
        final Map<Capability, ClientInfo> clientInformations = new HashMap<>();
        try {
            if (cursor.moveToFirst()) {
                do {
                    clientInformations.put(new Capability(null, null,
                                    CapabilitiesTable.getHash(cursor),
                                    CapabilitiesTable.getNode(cursor),
                                    CapabilitiesTable.getVersion(cursor)),
                            new ClientInfo(CapabilitiesTable.getType(cursor),
                                    CapabilitiesTable.getName(cursor),
                                    CapabilitiesTable.getNode(cursor),
                                    CapabilitiesTable.getFeatures(cursor)));
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }
        Application.getInstance().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onLoaded(clientInformations);
            }
        });
    }

    private void onLoaded(Map<Capability, ClientInfo> clientInformations) {
        this.clientInformations.putAll(clientInformations);
    }

    /**
     * Returns information about client.
     *
     * @param account
     * @param user    full JID.
     * @return <code>null</code> if there is no available information.
     */
    public ClientInfo getClientInfo(AccountJid account, FullJid user) {
        Capability capability = userCapabilities.get(account.toString(), user.toString());
        if (capability == null)
            return null;
        return clientInformations.get(capability);
    }

    /**
     * @param discoverInfo
     * @return Client information.
     */
    private Collection<String> getFeatures(DiscoverInfo discoverInfo) {
        Collection<String> features = new ArrayList<String>();
        for (DiscoverInfo.Feature feature : discoverInfo.getFeatures()) {
            features.add(feature.getVar());
        }
        return features;
    }

    /**
     * @param discoverInfo
     * @return Client information.
     */
    private ClientInfo getClientInfo(DiscoverInfo discoverInfo) {
        for (int useClient = 1; useClient >= 0; useClient--) {
            for (int useLanguage = 2; useLanguage >= 0; useLanguage--) {
                for (DiscoverInfo.Identity identity : discoverInfo.getIdentities()) {
                    if (useClient == 1 && !"client".equals(identity.getCategory())) {
                        continue;
                    }
                    if (useLanguage == 2 && !Stanza.getDefaultLanguage().equals(identity.getLanguage())) {
                        continue;
                    }
                    if (useLanguage == 1 && identity.getLanguage() != null) {
                        continue;
                    }
                    return new ClientInfo(identity.getType(), identity.getName(),
                            discoverInfo.getNode(), getFeatures(discoverInfo));
                }
            }
        }
        return new ClientInfo(null, null, null, getFeatures(discoverInfo));
    }

    /**
     * Requests disco info.
     */
    public void request(AccountJid account, Jid user) {
        Capability capability = new Capability(account, user, Capability.DIRECT_REQUEST_METHOD, null, null);
        userCapabilities.put(account.toString(), user.toString(), capability);
        request(account, user, capability);
    }

    /**
     * Requests disco info.
     */
    private void request(AccountJid account, Jid user, Capability capability) {
        for (DiscoverInfoRequest check : requests) {
            if (capability.equals(check.getCapability())) {
                return;
            }
        }
        DiscoverInfo packet = new DiscoverInfo();
        packet.setTo(user);
        packet.setType(Type.get);
        if (capability.getNode() != null && capability.getVersion() != null)
            packet.setNode(capability.getNode() + "#" + capability.getVersion());
        try {
            ConnectionManager.getInstance().sendStanza(account, packet);
        } catch (NetworkException e) {
            return;
        }
        requests.add(new DiscoverInfoRequest(account, user, packet.getStanzaId(), capability));
    }

    private boolean isValid(DiscoverInfo discoverInfo) {
        Set<DiscoverInfo.Identity> identities = new TreeSet<>(
                new Comparator<DiscoverInfo.Identity>() {

                    private int compare(String string1, String string2) {
                        return (string1 == null ? "" : string1)
                                .compareTo(string2 == null ? "" : string2);
                    }

                    @Override
                    public int compare(DiscoverInfo.Identity identity1, DiscoverInfo.Identity identity2) {
                        int result;
                        result = compare(identity1.getCategory(), identity2.getCategory());
                        if (result != 0) {
                            return result;
                        }
                        result = compare(identity1.getType(), identity2.getType());
                        if (result != 0) {
                            return result;
                        }
                        result = compare(identity1.getLanguage(), identity2.getLanguage());
                        if (result != 0) {
                            return result;
                        }
                        result = compare(identity1.getName(), identity2.getName());
                        if (result != 0) {
                            return result;
                        }
                        return 0;
                    }
                });
        for (DiscoverInfo.Identity identity : discoverInfo.getIdentities()) {
            if (!identities.add(identity)) {
                return false;
            }
        }

        Set<String> features = new HashSet<>();

        for (DiscoverInfo.Feature feature : discoverInfo.getFeatures()) {
            if (!features.add(feature.getVar())) {
                return false;
            }
        }
        Set<String> formTypes = new HashSet<>();
        for (ExtensionElement packetExtension : discoverInfo.getExtensions())
            if (packetExtension instanceof DataForm) {
                DataForm dataForm = (DataForm) packetExtension;
                String formType = null;
                for (FormField formField : dataForm.getFields()) {
                    if (FORM_TYPE.equals(formField.getVariable())) {
                        for (String value : formField.getValues()) {
                            if (formType != null && !formType.equals(value)) {
                                return false;
                            }
                            formType = value;
                        }
                    }
                }
                if (!formTypes.add(formType)) {
                    return false;
                }
            }
        return true;
    }

    private String calculateString(DiscoverInfo discoverInfo) {
        StringBuilder s = new StringBuilder();

        SortedSet<String> identities = new TreeSet<>();
        for (DiscoverInfo.Identity identity : discoverInfo.getIdentities()) {
            StringBuilder builder = new StringBuilder();
            builder.append(identity.getCategory());
            builder.append("/");
            String type = identity.getType();
            if (type != null) {
                builder.append(type);
            }
            builder.append("/");
            String lang = identity.getLanguage();
            if (lang != null) {
                builder.append(lang);
            }
            builder.append("/");
            String name = identity.getName();
            if (name != null) {
                builder.append(name);
            }
            identities.add(builder.toString());
        }
        for (String identity : identities) {
            s.append(identity);
            s.append("<");
        }

        SortedSet<String> features = new TreeSet<>();
        for (DiscoverInfo.Feature feature : discoverInfo.getFeatures()) {
            features.add(feature.getVar());
        }
        for (String feature : features) {
            s.append(feature);
            s.append("<");
        }

        // Maps prepared value to FORM_TYPE key.
        // Extensions with equal FORM_TYPEs are not allowed.
        SortedMap<String, String> extendeds = new TreeMap<>();
        for (ExtensionElement packetExtension : discoverInfo.getExtensions()) {
            if (packetExtension instanceof DataForm) {
                DataForm dataForm = (DataForm) packetExtension;
                // Fields with equal var are allowed for fixed type.
                SortedSet<FormField> formFields = new TreeSet<>(
                        new Comparator<FormField>() {
                            @Override
                            public int compare(FormField f1, FormField f2) {
                                // Var may not exists for fixed type.
                                String s1 = f1.getVariable();
                                String s2 = f2.getVariable();
                                return (s1 == null ? "" : s1)
                                        .compareTo(s2 == null ? "" : s2);
                            }
                        });
                String formType = null;
                for (FormField formField : dataForm.getFields()) {
                    if (FORM_TYPE.equals(formField.getVariable())) {
                        if (formField.getType() != FormField.Type.hidden) {
                            continue;
                        }
                        for (String value : formField.getValues()) {
                            formType = value;
                        }
                    } else {
                        formFields.add(formField);
                    }
                }
                if (formType == null) {
                    continue;
                }
                StringBuilder builder = new StringBuilder();
                builder.append(formType);
                builder.append("<");
                for (FormField formField : formFields) {
                    builder.append(formField.getVariable());
                    builder.append("<");
                    SortedSet<String> values = new TreeSet<>();
                    for (String value : formField.getValues()) {
                        values.add(value);
                    }
                    for (String value : values) {
                        builder.append(value);
                        builder.append("<");
                    }
                }
                extendeds.put(formType, builder.toString());
            }
        }
        for (Entry<String, String> extended : extendeds.entrySet()) {
            s.append(extended.getValue());
        }
        return s.toString();
    }

    @Override
    public void onAuthorized(ConnectionItem connection) {
        if (!(connection instanceof AccountItem))
            return;
        removeAccountInfo(((AccountItem) connection).getAccount());
    }

    @Override
    public void onAccountRemoved(AccountItem accountItem) {
        removeAccountInfo(accountItem.getAccount());
    }

    private void removeAccountInfo(AccountJid account) {
        userCapabilities.clear(account.toString());
        Iterator<Capability> iterator = clientInformations.keySet().iterator();
        while (iterator.hasNext())
            if (account.equals(iterator.next().getAccount())) {
                iterator.remove();
            }
    }

    @Override
    public void onDisconnect(ConnectionItem connection) {
        if (!(connection instanceof AccountItem))
            return;
        AccountJid account = ((AccountItem) connection).getAccount();
        Iterator<DiscoverInfoRequest> iterator = requests.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getAccount().equals(account))
                iterator.remove();
        }
    }

    public void onPresenceChanged(final AccountJid account, final Presence presence) {
        final Jid user = presence.getFrom();
        if (presence.getType() == Presence.Type.error)
            return;
        if (presence.getType() == Presence.Type.unavailable) {
            userCapabilities.remove(account.toString(), user.toString());
            return;
        }

        LogManager.i(this, "onPresenceChanged " + user);

        for (ExtensionElement packetExtension : presence.getExtensions()) {
            if (packetExtension instanceof CapsExtension) {
                CapsExtension capsExtension = (CapsExtension) packetExtension;
                if (capsExtension.getNode() == null || capsExtension.getVer() == null) {
                    continue;
                }
                Capability capability = new Capability(account, user,
                        capsExtension.getHash(), capsExtension.getNode(),
                        capsExtension.getVer());
                if (capability.equals(userCapabilities.get(account.toString(), user.toString()))) {
                    continue;
                }
                userCapabilities.put(account.toString(), user.toString(), capability);
                ClientInfo clientInfo = clientInformations.get(capability);
                if (clientInfo == null || clientInfo == INVALID_CLIENT_INFO) {
                    request(account, user, capability);
                }
            }
        }
    }

    @Override
    public void onStanza(ConnectionItem connection, Stanza packet) {
        if (!(connection instanceof AccountItem))
            return;
        final AccountJid account = ((AccountItem) connection).getAccount();
        final org.jxmpp.jid.Jid user = packet.getFrom();
        if (packet instanceof IQ) {
            IQ iq = (IQ) packet;
            if (iq.getType() != Type.error
                    && !(packet instanceof DiscoverInfo && iq.getType() == Type.result))
                return;
            String packetId = iq.getStanzaId();
            DiscoverInfoRequest request = null;
            Iterator<DiscoverInfoRequest> iterator = requests.iterator();
            while (iterator.hasNext()) {
                DiscoverInfoRequest check = iterator.next();
                if (check.getPacketId().equals(packetId)) {
                    request = check;
                    iterator.remove();
                    break;
                }
            }
            if (request == null || !request.getUser().equals(user))
                return;
            final Capability capability = request.getCapability();
            final ClientInfo clientInfo;
            if (iq.getType() == Type.error) {
                if (!Capability.DIRECT_REQUEST_METHOD.equals(capability
                        .getHash()))
                    // Don't save invalid replay if it wasn't direct request.
                    return;
                if (clientInformations.containsKey(capability))
                    return;
                clientInfo = INVALID_CLIENT_INFO;
            } else if (iq.getType() == Type.result) {
                DiscoverInfo discoverInfo = (DiscoverInfo) packet;
                if (capability.isSupportedHash() || capability.isLegacy()) {
                    if (capability.isLegacy()
                            || (isValid(discoverInfo) && capability
                            .getHashedValue(
                                    calculateString(discoverInfo))
                            .equals(capability.getVersion()))) {
                        clientInfo = getClientInfo(discoverInfo);
                        Application.getInstance().runInBackground(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        CapabilitiesTable.getInstance().write(
                                                capability.getHash(),
                                                capability.getNode(),
                                                capability.getVersion(),
                                                clientInfo.getType(),
                                                clientInfo.getName(),
                                                clientInfo.getFeatures());
                                    }
                                });
                    } else {
                        // Just wait for next presence from another entity.
                        return;
                    }
                } else {
                    clientInfo = getClientInfo(discoverInfo);
                }
            } else
                throw new IllegalStateException();
            clientInformations.put(capability, clientInfo);
            ArrayList<BaseEntity> entities = new ArrayList<>();
            for (NestedMap.Entry<Capability> entry : userCapabilities) {
                if (capability.equals(entry.getValue())) {
                    try {
                        entities.add(new BaseEntity(account,
                                UserJid.from(JidCreate.bareFrom(entry.getSecond()))));
                    } catch (XmppStringprepException e) {
                        LogManager.exception(this, e);
                    }
                }
            }
            RosterManager.onContactsChanged(entities);
        }
    }
}
