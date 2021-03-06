// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2019 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package org.dogtagpki.server.tps;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;

import org.dogtagpki.server.tps.installer.TPSInstaller;
import org.mozilla.jss.netscape.security.util.Utils;
import org.mozilla.jss.ssl.SSLCertificateApprovalCallback;

import com.netscape.certsrv.account.AccountClient;
import com.netscape.certsrv.authentication.EAuthException;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.IConfigStore;
import com.netscape.certsrv.base.PKIException;
import com.netscape.certsrv.base.ResourceNotFoundException;
import com.netscape.certsrv.client.ClientConfig;
import com.netscape.certsrv.client.PKIClient;
import com.netscape.certsrv.key.KeyData;
import com.netscape.certsrv.system.AdminSetupRequest;
import com.netscape.certsrv.system.AdminSetupResponse;
import com.netscape.certsrv.system.DatabaseSetupRequest;
import com.netscape.certsrv.system.FinalizeConfigRequest;
import com.netscape.certsrv.system.TPSConnectorClient;
import com.netscape.certsrv.system.TPSConnectorData;
import com.netscape.certsrv.user.UserResource;
import com.netscape.certsrv.usrgrp.IUser;
import com.netscape.cms.servlet.csadmin.Configurator;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.apps.CMSEngine;
import com.netscape.cmscore.selftests.SelfTestSubsystem;
import com.netscape.cmscore.usrgrp.UGSubsystem;
import com.netscape.cmsutil.crypto.CryptoUtil;
import com.netscape.cmsutil.xml.XMLObject;

public class TPSConfigurator extends Configurator {

    public TPSConfigurator(CMSEngine engine) {
        super(engine);
    }

    public void configureCAConnector(URI caURI, String nickname) {

        // TODO: get installer from session
        TPSInstaller installer = new TPSInstaller();
        installer.configureCAConnector(caURI, nickname);
    }

    public void configureTKSConnector(URI tksURI, String nickname) {

        // TODO: get installer from session
        TPSInstaller installer = new TPSInstaller();
        installer.configureTKSConnector(tksURI, nickname);
    }

    public void configureKRAConnector(URI kraURI, String nickname, boolean keygen) {

        // TODO: get installer from session
        TPSInstaller installer = new TPSInstaller();
        installer.configureKRAConnector(keygen, kraURI, nickname);
    }

    @Override
    public void initializeDatabase(DatabaseSetupRequest request) throws EBaseException {

        super.initializeDatabase(request);

        // Enable subsystems after database initialization.
        CMSEngine engine = CMS.getCMSEngine();

        engine.setSubsystemEnabled(SelfTestSubsystem.ID, true);
    }

    @Override
    public void setupAdmin(AdminSetupRequest request, AdminSetupResponse response) throws Exception {

        super.setupAdmin(request, response);

        logger.debug("Adding all profiles to TPS admin user");

        CMSEngine engine = CMS.getCMSEngine();
        UGSubsystem system = (UGSubsystem) engine.getSubsystem(UGSubsystem.ID);

        String adminID = request.getAdminUID();
        IUser user = system.getUser(adminID);

        List<String> profiles = new ArrayList<String>();
        profiles.add(UserResource.ALL_PROFILES);

        user.setTpsProfiles(profiles);
        system.modifyUser(user);
    }

    @Override
    public void finalizeConfiguration(FinalizeConfigRequest request) throws Exception {

        URI secdomainURI = new URI(request.getSecurityDomainUri());
        URI caURI = request.getCaUri();
        URI tksURI = request.getTksUri();
        URI kraURI = request.getKraUri();

        boolean keygen = request.getEnableServerSideKeyGen().equalsIgnoreCase("true");

        String nickname = cs.getString("tps.subsystem.nickname");
        String tokenname = cs.getString("tps.subsystem.tokenname");

        if (!CryptoUtil.isInternalToken(tokenname)) {
            nickname = tokenname + ":" + nickname;
        }

        logger.debug("TPSConfigurator: subsystem cert: " + nickname);

        logger.info("TPSConfigurator: Configuring CA connector");
        configureCAConnector(caURI, nickname);

        logger.info("TPSConfigurator: Configuring TKS connector");
        configureTKSConnector(tksURI, nickname);

        logger.info("TPSConfigurator: Configuring KRA connector");
        configureKRAConnector(kraURI, nickname, keygen);

        try {
            logger.info("TPSConfigurator: Registering TPS to CA: " + caURI);
            registerUser(secdomainURI, caURI, "ca");

        } catch (Exception e) {
            String message = "Unable to register TPS to CA: " + e.getMessage();
            logger.error(message, e);
            throw new PKIException(message, e);
        }

        try {
            logger.info("TPSConfigurator: Registering TPS to TKS: " + tksURI);
            registerUser(secdomainURI, tksURI, "tks");

        } catch (Exception e) {
            String message = "Unable to register TPS to TKS: " + e.getMessage();
            logger.error(message, e);
            throw new PKIException(message, e);
        }

        if (keygen) {

            try {
                logger.info("TPSConfigurator: Registering TPS to KRA: " + kraURI);
                registerUser(secdomainURI, kraURI, "kra");

            } catch (Exception e) {
                String message = "Unable to register TPS to KRA: " + e.getMessage();
                logger.error(message, e);
                throw new PKIException(message, e);
            }

            String transportCert;
            try {
                logger.info("TPSConfigurator: Retrieving transport cert from KRA");
                transportCert = getTransportCert(secdomainURI, kraURI);

            } catch (Exception e) {
                String message = "Unable to retrieve transport cert from KRA: " + e.getMessage();
                logger.error(message, e);
                throw new PKIException(message, e);
            }

            try {
                logger.info("TPSConfigurator: Importing transport cert into TKS");
                exportTransportCert(secdomainURI, tksURI, transportCert);

            } catch (Exception e) {
                String message = "Unable to import transport cert into TKS: " + e.getMessage();
                logger.error(message, e);
                throw new PKIException(message, e);
            }
        }

        try {
            String doImportStr = request.getImportSharedSecret();
            logger.debug("TPSConfigurator: importSharedSecret:" + doImportStr);

            boolean doImport = false;
            if ("true".equalsIgnoreCase(doImportStr)) {
                doImport = true;
            }

            logger.info("TPSConfigurator: Generating shared secret in TKS");
            getSharedSecret(
                    tksURI.getHost(),
                    tksURI.getPort(),
                    doImport);

        } catch (Exception e) {
            String message = "Unable to generate shared secret in TKS: " + e.getMessage();
            logger.error(message, e);
            throw new PKIException(message, e);
        }

        super.finalizeConfiguration(request);
    }

    public String getTransportCert(URI secdomainURI, URI kraUri) throws Exception {

        logger.debug("TPSConfigurator: getTransportCert() start");

        CMSEngine engine = CMS.getCMSEngine();
        String sessionId = engine.getConfigSDSessionId();

        MultivaluedMap<String, String> content = new MultivaluedHashMap<String, String>();
        content.putSingle("xmlOutput", "true");
        content.putSingle("sessionID", sessionId);
        content.putSingle("auth_hostname", secdomainURI.getHost());
        content.putSingle("auth_port", secdomainURI.getPort() + "");

        String c = post(
                kraUri.getHost(),
                kraUri.getPort(),
                true,
                "/kra/admin/kra/getTransportCert",
                content, null, null);

        if (c == null) {
            return null;
        }

        ByteArrayInputStream bis = new ByteArrayInputStream(c.getBytes());
        XMLObject parser = new XMLObject(bis);
        String status = parser.getValue("Status");

        if (!status.equals(SUCCESS)) {
            return null;
        }

        return parser.getValue("TransportCert");
    }

    public void exportTransportCert(URI secdomainURI, URI targetURI, String transportCert) throws Exception {

        CMSEngine engine = CMS.getCMSEngine();
        String sessionId = engine.getConfigSDSessionId();

        String securePort = cs.getString("service.securePort", "");
        String machineName = cs.getHostname();
        String name = "transportCert-" + machineName + "-" + securePort;

        MultivaluedMap<String, String> content = new MultivaluedHashMap<String, String>();
        content.putSingle("name", name);
        content.putSingle("xmlOutput", "true");
        content.putSingle("sessionID", sessionId);
        content.putSingle("auth_hostname", secdomainURI.getHost());
        content.putSingle("auth_port", secdomainURI.getPort() + "");
        content.putSingle("certificate", transportCert);

        String targetURL = "/tks/admin/tks/importTransportCert";

        String response = post(
                targetURI.getHost(),
                targetURI.getPort(),
                true,
                targetURL,
                content, null, null);

        if (response == null || response.equals("")) {
            logger.error("TPSConfigurator: The server " + targetURI + " is not available");
            throw new IOException("The server " + targetURI + " is not available");
        }

        ByteArrayInputStream bis = new ByteArrayInputStream(response.getBytes());
        XMLObject parser = new XMLObject(bis);

        String status = parser.getValue("Status");
        logger.debug("TPSConfigurator: status: " + status);

        if (status.equals(AUTH_FAILURE)) {
            throw new EAuthException(AUTH_FAILURE);
        }

        if (!status.equals(SUCCESS)) {
            String error = parser.getValue("Error");
            throw new IOException(error);
        }

        logger.debug("TPSConfigurator: Successfully added transport cert to " + targetURI);
    }

    public void getSharedSecret(String tksHost, int tksPort, boolean importKey) throws Exception {

        CMSEngine engine = CMS.getCMSEngine();

        String host = cs.getString("service.machineName");
        String port = cs.getString("service.securePort");
        String dbDir = cs.getInstanceDir() + "/alias";
        String dbNick = cs.getString("tps.cert.subsystem.nickname");

        String passwordFile = cs.getString("passwordFile");
        IConfigStore psStore = engine.createFileConfigStore(passwordFile);
        String dbPass = psStore.getString("internal");

        ClientConfig config = new ClientConfig();
        config.setServerURL("https://" + tksHost + ":" + tksPort);
        config.setNSSDatabase(dbDir);
        config.setNSSPassword(dbPass);
        config.setCertNickname(dbNick);

        PKIClient client = new PKIClient(config, null);

        // Ignore the "UNTRUSTED_ISSUER" and "CA_CERT_INVALID" validity status
        // during PKI instance creation since we are using an untrusted temporary CA cert.
        client.addIgnoredCertStatus(SSLCertificateApprovalCallback.ValidityStatus.UNTRUSTED_ISSUER);
        client.addIgnoredCertStatus(SSLCertificateApprovalCallback.ValidityStatus.CA_CERT_INVALID);

        AccountClient accountClient = new AccountClient(client, "tks");
        TPSConnectorClient tpsConnectorClient = new TPSConnectorClient(client, "tks");

        accountClient.login();
        TPSConnectorData data = null;
        try {
            data = tpsConnectorClient.getConnector(host, port);
        } catch (ResourceNotFoundException e) {
            // no connector exists
            data = null;
        }

        // The connId or data.getID will be the id of the shared secret
        KeyData keyData = null;
        if (data == null) {
            data = tpsConnectorClient.createConnector(host, port);
            keyData = tpsConnectorClient.createSharedSecret(data.getID());
        } else {
            String connId = data.getID();
            keyData = tpsConnectorClient.getSharedSecret(connId);
            if (keyData != null) {
                keyData = tpsConnectorClient.replaceSharedSecret(connId);
            } else {
                keyData = tpsConnectorClient.createSharedSecret(connId);
            }
        }

        accountClient.logout();

        logger.debug("TPSConfigurator: importKey: " + importKey);
        String nick = "TPS-" + host + "-" + port + " sharedSecret";

        if (importKey) {
            logger.debug("TPSConfigurator: About to attempt to import shared secret key.");
            byte[] sessionKeyData = Utils.base64decode(keyData.getWrappedPrivateData());
            byte[] sharedSecretData = Utils.base64decode(keyData.getAdditionalWrappedPrivateData());

            try {
                CryptoUtil.importSharedSecret(sessionKeyData, sharedSecretData, dbNick, nick);

            } catch (Exception e) {
                logger.warn("Failed to automatically import shared secret: " + e.getMessage(), e);
                logger.warn("Please follow the manual procedure");
            }

            // this is not needed if we are using a shared database with
            // the tks.
        }

        // store the new nick in CS.cfg
        cs.putString("conn.tks1.tksSharedSymKeyName", nick);
        cs.commit(false);
    }
}
