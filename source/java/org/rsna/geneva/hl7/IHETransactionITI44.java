package org.rsna.geneva.hl7;

import org.w3c.dom.Element;
import org.w3c.dom.Document;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.io.StringReader;
import java.net.URL;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.stream.StreamResult;
import org.xml.sax.InputSource;
import javax.xml.soap.SOAPMessage;
import javax.xml.soap.SOAPHeader;
import javax.xml.soap.SOAPBody;
import javax.xml.soap.SOAPFault;


//////////////////////

import javax.xml.transform.OutputKeys;
//import javax.xml.transform.Transformer;
//import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
//import javax.xml.transform.stream.StreamResult;

import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;
import org.openhealthtools.ihe.atna.auditor.PIXSourceAuditor;
import org.openhealthtools.ihe.pix.source.v3.V3PixSource;
import org.openhealthtools.ihe.pix.source.v3.V3PixSourceAcknowledgement;
import org.openhealthtools.ihe.pix.source.v3.V3PixSourceMergePatients;
import org.openhealthtools.ihe.pix.source.v3.V3PixSourceRecordAdded;

//////////////////////

import org.rsna.geneva.main.Configuration;
import org.rsna.geneva.misc.Registration;

public class IHETransactionITI44 implements PatientIdFeed {
    private static String name = "Patient Id Feed, ITI44, hl7v3";
    private RegistrationXml regx;
    private String hl7URL;
    private String receivingApplication;
    private String receivingFacility;


    private Registration reg;
    private Configuration cfg;
    private Transformer transformer;

    private SOAPWrapper wrapper;
    private String soapVersion;
    private boolean saveMessages;
    private int timeout;

    public IHETransactionITI44 (Element el,
                                Registration reg,
                                Configuration cfg)
    {
        try {
            regx = new RegistrationXml( el, reg, cfg);
            transformer
                = IHETransactionTransformerFactory.getITI44Transformer();
        }
        catch( Exception e) {
            throw new IllegalArgumentException(
                "Error creating ITI44 Transaction.", e);
        }

        this.reg = reg;
        this.cfg = cfg;
        wrapper = null;
        saveMessages = false;

        soapVersion = el.getAttribute("soapVersion").trim();
        if( soapVersion == null || soapVersion.equals("")) {
            soapVersion = "SOAP_1_2";
        }

        hl7URL = el.getAttribute("hl7URL").trim();
        if( hl7URL == null || hl7URL.equals("")) {
            throw new IllegalArgumentException(
                "Missing hl7URL attribute.");
        }
        receivingApplication = el.getAttribute("receivingApplication").trim();
        receivingFacility    = el.getAttribute("receivingFacility").trim();

    }

    public String getName() {
        return name;
    }

    public void setAssigningAuthority( String s) {
        regx.setAssigningAuthority( s);
    }

    public void setPatientId( String s) {
        regx.setPatientId( s);
    }

    public void setSaveMessages( boolean b) {
        saveMessages = b;
    }

    public void setTimeout( int i) {
        timeout = i;
    }

    /**
     * send this transaction.
     */
    public IHETransactionResponse send() {
System.out.println("IHETransactionResponse::send enter X");
System.out.println(" HL7URL: " + hl7URL);

	// NIST 129.6.24.143
	//java.net.URI u = java.net.URI.create("http://129.6.24.143:9090");
	// Oracle 24.205.75.156
	//java.net.URI u = java.net.URI.create("http://24.205.75.156:8080/PIXManager_Service/PIXManager");

	java.net.URI u = java.net.URI.create(hl7URL);
	V3PixSource pixSource = new V3PixSource(u);

	//String senderApplication = "1.2.840.114350.1.13.99998.8735";
	//String senderFacility    = "1.2.840.114350.1.13.99998";
	//String organizationalRoot= "1.2.840.114350.10";
	//String rcvrApplication = "2.16.840.1.113883.3.72.6.5.100.144";
	//String rcvrFacility    = "2.16.840.1.113883.3.72.6.1";

	String senderApplication = cfg.senderDeviceId;
	String senderFacility    = cfg.senderDeviceName;
	String organizationalRoot= cfg.getUIDRootNoTrailer();
	String rcvrApplication   = receivingApplication;
	String rcvrFacility      = receivingFacility;


	V3PixSourceRecordAdded v3RecordAddedMessage =
		new V3PixSourceRecordAdded(senderApplication, senderFacility,
				rcvrApplication , organizationalRoot);

	// For NIST
	//v3RecordAddedMessage.addPatientID("PIX", "2.16.840.1.113883.3.72.5.9.1", "NIST2010");
	// For Oracle
	v3RecordAddedMessage.addPatientID(regx.getPatientId(), regx.getAssigningAuthorityOIDOnly(), "IHE");
	v3RecordAddedMessage.addPatientName(reg.familyName, reg.givenName, "", "", "");
	v3RecordAddedMessage.setPatientGender(reg.sex);
	v3RecordAddedMessage.setPatientBirthTime(reg.birthdate);
	v3RecordAddedMessage.addPatientAddress(reg.street,
				reg.city,
				"",
				reg.state,
				"",
				reg.zip,
				"");
	//v3RecordAddedMessage.addPatientName("ALPHA", "ALAN", "", "", "");
	//v3RecordAddedMessage.setPatientGender("F");
	//v3RecordAddedMessage.setPatientBirthTime("19601212");
	//v3RecordAddedMessage.addPatientAddress("4525 Scott",
	//			"St Louis",
	//			"",
	//			"MO",
	//			"",
	//			"63110",
	//			"");

	v3RecordAddedMessage.setScopingOrganization("1.2.3", "GENEVA", "314-555-1000");

	V3PixSourceAcknowledgement v3PixAck = null;
System.out.println("Ready to send");

        try {
	    v3PixAck = pixSource.sendRecordAdded(v3RecordAddedMessage);
System.out.println("Sent, must not have thrown an exception");
            return new IHETransactionResponse( 0, "Success: " + "dude");
        }
        catch( Exception e) {
System.out.println("Exception " + dumpException(e));
            return new IHETransactionResponse( 1, dumpException(e));
        }
    }

    public String dumpException( Exception e) {
        if( e == null) return "";

        StringBuffer sb
            = new StringBuffer( "Failed: " + e.getMessage() + "\n");
        StackTraceElement[] ste = e.getStackTrace();
        for( StackTraceElement s : ste) {
            sb.append(s + "\n");
        }
        Throwable cause = e.getCause();
        while( cause != null ) {
            sb.append("Cause: " + cause.getMessage() + "\n");
            //ste = cause.getStackTrace();
            //for( StackTraceElement s : ste) {
            //    sb.append(s + "\n");
            //}
            cause = cause.getCause();
        }
        return sb.toString();
    }

    public String toString() {
        if( wrapper == null) {
            return "regx: " + regx.toString();
        }
        else {
            return wrapper.toString();
        }
    }
}

