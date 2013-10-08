/*---------------------------------------------------------------
*  Copyright 2005 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense)
*----------------------------------------------------------------*/

package org.rsna.geneva.elements;

import java.io.*;
import java.net.URI;
import javax.xml.soap.SOAPMessage;
import org.w3c.dom.Element;
import org.apache.log4j.Logger;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.TimeZone;

//JAXP
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.Source;
import javax.xml.transform.Result;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.sax.SAXResult;

// FOP
import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FOPException;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.FormattingResults;
import org.apache.fop.apps.MimeConstants;
import org.apache.fop.apps.PageSequenceResults;

import org.rsna.geneva.main.Configuration;
import org.rsna.geneva.misc.Registration;
import org.rsna.geneva.misc.RegSysEvent;
import org.rsna.util.XmlUtil;
import org.rsna.util.StringUtil;
import org.rsna.util.FileUtil;
import org.rsna.geneva.hl7.*;
import org.rsna.geneva.misc.RegSysEvent;
import org.rsna.geneva.misc.XDSSoapRequest;
import org.rsna.util.Base64;

// OHT software
import org.openhealthtools.ihe.utils.OID;
import org.openhealthtools.ihe.xds.document.DocumentDescriptor;
import org.openhealthtools.ihe.xds.document.XDSDocument;
import org.openhealthtools.ihe.xds.document.XDSDocumentFromFile;
import org.openhealthtools.ihe.xds.response.XDSResponseType;
import org.openhealthtools.ihe.xds.response.XDSErrorType;
import org.openhealthtools.ihe.xds.source.SubmitTransactionData;
import org.openhealthtools.ihe.xds.metadata.SubmissionSetType;
import org.openhealthtools.ihe.xds.source.B_Source;

public class Repository extends Product {

	static final Logger logger = Logger.getLogger(Repository.class);

	public String type = "Repository";

	public String globalAssigningAuthority;
	public String globalAssigningAuthorityOID;
	public String soapURL;
	public boolean sendsSOAP;
	public String soapVersion;
	public int docsetDelay = 0;

	public Repository(Element el) {
		super(el);
		this.enabled = !el.getAttribute("enabled").trim().equals("no");
		this.globalAssigningAuthority = el.getAttribute("globalAssigningAuthority").trim();
		this.globalAssigningAuthority = getGlobalAssigningAuthority();
		this.globalAssigningAuthorityOID = el.getAttribute("globalAssigningAuthorityOID").trim();
		this.globalAssigningAuthorityOID = getGlobalAssigningAuthorityOID();
		this.soapURL = el.getAttribute("soapURL").trim();
		this.sendsSOAP = !el.getAttribute("sendsSOAP").trim().equals("no");
		this.soapVersion = el.getAttribute("soapVersion").trim();
		if (this.soapVersion.equals("")) this.soapVersion = "SOAP_1_2";
		try { this.docsetDelay = Integer.parseInt(el.getAttribute("docsetDelay").trim()); }
		catch (Exception ex) { this.docsetDelay = 0; }
	}

	public String getGlobalAssigningAuthority() {
		if (!globalAssigningAuthority.equals(""))
			return globalAssigningAuthority;
		return Configuration.getInstance().getGlobalAssigningAuthority();
	}

	public String getGlobalAssigningAuthorityOID() {
		if (!globalAssigningAuthorityOID.equals(""))
			return globalAssigningAuthorityOID;
		return Configuration.getInstance().getGlobalAssigningAuthorityOID();
	}

	public void appendTableRow(StringBuffer sb) {
		sb.append("<tr><td><b>" + type + "</b><br>ID: "+id+"</td><td>");
		sb.append("<table border=\"0\" width=\"100%\">");
		appendDataRows(sb);
		sb.append("</table>");
		sb.append("</td></tr>");
	}

	public void appendDataRows(StringBuffer sb) {
		sb.append("<tr><td width=\"165\">enabled:</td><td>"+(enabled?"yes":"no")+"</td></tr>");
		sb.append("<tr><td>Global Assigning Authority:</td><td>"+globalAssigningAuthority+"</td></tr>");
		sb.append("<tr><td>SOAP URL:</td><td>"+soapURL+"</td></tr>");
		sb.append("<tr><td>Sends SOAP Message:</td><td>"+(sendsSOAP?"yes":"no")+"</td></tr>");
		sb.append("<tr><td>Thread Startup Delay (ms):</td><td>"+startupDelay+"</td></tr>");
		sb.append("<tr><td>Docset Delay (ms):</td><td>"+docsetDelay+"</td></tr>");
	}

	void processRegistration(Registration reg) {
		//Process the DocSets for this Repository
		DocSet[] docSets = Configuration.getInstance().getDocSets();
		if ((docSets.length > 0) && (docsetDelay > 0)) {
			try { Thread.sleep(docsetDelay); }
			catch (Exception ignore) { }
		}
		for (int i=0; i<docSets.length; i++) {
			if (docSets[i].enabled && docSets[i].repositoryID.equals(id)) {
				processDocSet(reg,docSets[i]);
			}
		}
	}

	void processDocSet(Registration reg, DocSet docSet) {
	    System.out.println("processDocSet");
		Configuration config = Configuration.getInstance();
		//First make sure that the sex of this registrant
		//matches the constraint of this docset.
		String sex = docSet.sex;
		String regsex = reg.sex.toUpperCase().trim();
		boolean accepted = sex.equals("BOTH") || (regsex.length()==0)  || sex.startsWith(regsex.substring(0,1));
		if (!accepted) return;

		//A DocSet is a directory containing files for producing
		//a submission to a repository. There are three types of
		//DocSets:
		//
		//  1. One with files for producing a CDA only.
		//	2. One with files for producing a PDF and a CDA
		//	   which encapsulates the PDF.
		//  3. One with files for producing a PDF only.
		//
		//Types 1 and 2 are XDS-compliant. Type 3 is XDSI-compliant.
		//
		//In each case, the files (for the PDF and the CDA, as required)
		//are processed to insert information from the registration
		//into them where required.
		//
		//The result of the processing is an attachment which is
		//wrapped in XDS metadata and transmitted to the repository
		//in a SOAP request.

		logger.debug("Logger - Debug");
		System.out.println("About to try");

		try {
			//Make a temp directory.
			File temp = getTempDir(docSet.directoryFile);
			System.out.println("temp " + temp.toString());

			//Declare a File for the attachment.
			File attachment = null;
			String mimeType = "text/xml";

			//Set up the path so we point to the directory.
			//This is necessary because we will be working in
			//a temporary subdirectory and the transform will
			//need to reference objects in the docSet's directory.
			String path = docSet.directory.replaceAll("\\\\","/");
			System.out.println("Docset temp folder: " + path);

			//Setup the parameters for the transformations.
			//The same parameters are used in all the transformations
			//(xml to FO and doc to metadata).
			String date = docSet.date;
			if (date.indexOf("*") != -1) date = config.today();
			String uuid = config.getUUID();
			String[] params = new String[] {
				"path",					path,
				"patient-name",			reg.getName(),
				"full-name",			reg.getFullName(),
				"given-name",			reg.givenName,
				"family-name",			reg.familyName,
				"patient-id",			reg.globalID,
				"assigning-authority",	globalAssigningAuthority,
				"assigning-authority-OID",	globalAssigningAuthorityOID,
				"institution-name",		docSet.institutionName,
				"document-id",			config.getAccessionNumber(),
				"title",				docSet.title,
				"date",					date,
				"time",					config.now(),
				"street",				reg.street,
				"city",					reg.city,
				"state",				reg.state,
				"zip",					reg.zip,
				"country",				reg.country,
				"sex",					reg.sex,
				"birth-date",			reg.birthdate,
				"uuid",					uuid,
				"uid1",					config.getUID(),
				"uid2",					config.getUID(),
				"uid3",					config.getUID(),
				"uid4",					config.getUID(),
				"pdf",					""	//This param must be last in the array (see *** below).
			};

			String pdfMessage = "";
			String payloadFileName = "";
			System.out.println("Global patient ID: " + reg.globalID);

			//If the pdfSource.xml file (containing the instructions for
			//producing the PDF file) exists, process it and set the attachment
			//to point to the resulting PDF.
			File pdfSource = new File(docSet.directoryFile,"pdfSource.xml");
			if (pdfSource.exists()) {

				//The process for producing a PDF is first to transform the
				//pdfSource.xml file into a formatting objects file and then
				//to convert the fcFile into a PDF.
				File foFile = File.createTempFile("DS-",".fo",temp);

				//Get the transform program.
				File pdfSourceToFO = new File("docxsl/pdfSourceToFO.xsl");

				//Create the FO file and save it to disk
				FileUtil.setText(
					foFile,
					XmlUtil.toString(
						XmlUtil.getTransformedDocument(
							pdfSource, pdfSourceToFO, params
						)
					)
				);

				//Now convert the FO file to a PDF
				File pdfFile = new File(foFile.getParentFile(),foFile.getName()+".pdf");
				int pages = convertFO2PDF(foFile,pdfFile);
				pdfMessage = "PDF ("+pages+" page"+((pages!=1)?"s":"")+") created.<br>";

				//Now get the PDF as a byte array and encode it as a B64 string
				//in case we will be encapsulating the file in a CDA.
				byte[] pdfBytes = FileUtil.getBytes(pdfFile);
				String pdfB64 = Base64.encodeToString(pdfBytes);

				//Put the encoded PDF in the parameters.
				//The entry is already there, but the value is empty.
		/***/	params[params.length-1] = pdfB64;	/***/

				//Since we actually produced a PDF and don't yet know whether we will
				//produce a CDA, let the attachment point to the PDF for now.
				attachment = pdfFile;
				mimeType = "application/pdf";
			} else {
			  System.out.println("No PDF to process");
			}

			//If the cdaSource.xml file (containing the instructions for
			//producing the CDA file) exists, process it and set the attachment
			//to point to the resulting CDA.
			File cdaSource = new File(docSet.directoryFile,"cdaSource.xml");
			if (cdaSource.exists()) {
				System.out.println("Going to make a cda");

				File cda = new File(temp,"cda.xml");
				payloadFileName = cda.getPath();

				//Get the transform program.
				File cdaSourceToCDA = new File("docxsl/cdaSourceToCDA.xsl");

				//Create the CDA
				FileUtil.setText(
					cda,
					XmlUtil.toString(
						XmlUtil.getTransformedDocument(
							cdaSource, cdaSourceToCDA, params
						)
					)
				);

				//Since we actually produced a CDA, make it the attachment.
				//If a PDF was produced, it must have been encapsulated in the CDA.
				attachment = cda;
				mimeType = "text/xml";
			}

			//Create the metadata file for transmission to the Repository.
			File docEntrySource = new File(docSet.directoryFile,"docEntrySource.xml");
			File docEntry = new File(temp,"docEntry.xml");
			File docEntrySourceToDocEntry = new File("docxsl/docEntrySourceToDocEntry.xsl");
			if (!docEntrySourceToDocEntry.exists() || !docEntrySourceToDocEntry.isFile()) return;
			FileUtil.setText(
				docEntry,
				XmlUtil.toString(
					XmlUtil.getTransformedDocument(
						docEntrySource, docEntrySourceToDocEntry, params
					)
				)
			);

			File submissionSetSource = new File(docSet.directoryFile,"submissionSetSource.xml");
			File submissionSet = new File(temp,"submissionSet.xml");
			File submissionSetSourceToSubmissionSet = new File("docxsl/submissionSetSourceToSubmissionSet.xsl");
			System.out.println("Submission set source XSL " + submissionSetSourceToSubmissionSet.getAbsolutePath());
			if (!submissionSetSourceToSubmissionSet.exists() || !submissionSetSourceToSubmissionSet.isFile()) return;
			FileUtil.setText(
				submissionSet,
				XmlUtil.toString(
					XmlUtil.getTransformedDocument(
						submissionSetSource, submissionSetSourceToSubmissionSet, params
					)
				)
			);

			String responseText = "transmission disabled";
			B_Source source = null;
			if (sendsSOAP) {
				responseText = "transmission attempted";
				System.out.println("Make a new txnData");
				String organizationalOID = config.getUIDRoot() + "10";
				SubmitTransactionData txnData = new SubmitTransactionData();
				System.out.println("make XDS document from payload: " + payloadFileName);
				XDSDocument clinicalDocument = new XDSDocumentFromFile(
					DocumentDescriptor.CDA_R2, payloadFileName);

				FileInputStream fis = new FileInputStream(docEntry);
				String docEntryUUID = txnData.loadDocumentWithMetadata(clinicalDocument, fis);
				fis.close();
				logger.debug("loadDocumentWithMetadata " + docEntryUUID);
				System.out.println("loadDocumentWithMetadata " + docEntryUUID);

				String uniqueID = OID.createOIDGivenRoot(organizationalOID, 64);
				txnData.getDocumentEntry(docEntryUUID).setUniqueId(uniqueID);
				System.out.println("Doc Entry UUID: " + uniqueID);
				logger.debug ("Done setting documentEntry metadata for " );

				fis = new FileInputStream(submissionSet);
				txnData.loadSubmissionSet(fis);
				fis.close();
				logger.debug("txtData.loadSubmissionSet");
				uniqueID = OID.createOIDGivenRoot(organizationalOID, 64);
				txnData.getSubmissionSet().setUniqueId(uniqueID);
				System.out.println("Submission Set Unique ID: " + uniqueID);
				txnData.getSubmissionSet().setSubmissionTime(Repository.formGMT_DTM());
				System.out.println("Set submission time: " + Repository.formGMT_DTM());
				txnData.saveMetadataToFile("fail-meta.xml");
				System.out.println("Saved metadata to fail-meta.xml");
				txnData.getSubmissionSet().setSourceId(organizationalOID);
				System.out.println("Set source ID in submission set: " + organizationalOID);

				logger.debug("SOAP URL: " + soapURL);
				logger.debug("Make a new B_Source");
				System.out.println("About to make a new B_Source with URL: " + soapURL);
				source = new org.openhealthtools.ihe.xds.source.B_Source(
					new URI(soapURL));
				System.out.println("Submitting document " + soapURL);
				XDSResponseType response = null;
				response = source.submit(txnData);
				System.out.println("Doc submitted, check for errors");
				responseText = "transmission completed";

				if (response == null) {
					//System.out.println("Null response back from submitting doc");
				} else if (response.getErrorList() == null) {
					//System.out.println("getErrorList is null");
				} else if (response.getErrorList().getError() == null) {
					//System.out.println("getErrorList.getError is null");
				} else {
					System.out.println("Returned " + response.getErrorList().getError().size() + " errors.");
					responseText = "XDS.b transmission encountered errors:";
					java.util.List<XDSErrorType> lst = response.getErrorList().getError();
					Iterator<XDSErrorType> it = lst.iterator();
					int ix = 1;
					while (it.hasNext()) {
						responseText += " (" + ix + ")" + it.next().getValue();
						ix++;
					}
					responseText += " (End of XDS.b xmit errors)";
				}
				System.out.println("Done");

			}
			source = null;
//			if (sendsSOAP) {
//				//Transmit the metadata and the attachment to the Repository.
//				XDSSoapRequest xsr = new XDSSoapRequest(
//						metadata,
//						attachment,
//						mimeType,
//						soapVersion);
//				SOAPMessage response = xsr.send(soapURL);
//				responseText = xsr.getText(response);
//			}
//
			//Finally, log an event.
			RegSysEvent event =
				new RegSysEvent(
						this,
						toStatus(responseText),
						RegSysEvent.TYPE_SOAP,
						"DocSet "+docSet.id+" processed.<br>"
							+pdfMessage
							+"SOAP Response: "+StringUtil.displayable(responseText)
					);
			config.getEventLog().append(event);
			// - smmif (isOK(responseText)) FileUtil.deleteAll(temp);

			//At this point, we are done. delete the temp directory.
			//Note that if an exception occurs, the temp directory will
			//not be deleted. This is intentional.
			FileUtil.deleteAll(temp);
		}
		catch (Exception ex) {
			//Get the top of the stack track
			StackTraceElement[] ste = ex.getStackTrace();
			String topElement = ste[0].toString();
			//Log an event
			RegSysEvent event =
				new RegSysEvent(
						this,
						RegSysEvent.STATUS_ERROR,
						RegSysEvent.TYPE_SOAP,
						"Unable to process DocSet "+docSet.id+
						"<br>"+StringUtil.displayable(ex.getMessage())+
						"<br>"+StringUtil.displayable(topElement)
					);
			config.getEventLog().append(event);
			//logger.warn("Unable to process DocSet "+docSet.id,ex);
		}
	}

	void submitDocumentXDSb(File payload, DocumentDescriptor documentDescriptor,
		File metadataFolder,
		Registration reg, String date,
		String institutionName, String documentTitle) {
	    Configuration config = Configuration.getInstance();
	    System.out.println("submitDocumentXDSb");
	    System.out.println("<" + payload.toString() + ">");

	    try {

		if (date.indexOf("*") != -1) date = config.today();
		String uuid = config.getUUID();
		String[] params = new String[] {
//				"path",				path,
				"patient-name",			reg.getName(),
				"full-name",			reg.getFullName(),
				"given-name",			reg.givenName,
				"family-name",			reg.familyName,
				"patient-id",			reg.globalID,
				"assigning-authority",		globalAssigningAuthority,
				"assigning-authority-OID",	globalAssigningAuthorityOID,
				"institution-name",		institutionName,
				"document-id",			config.getAccessionNumber(),
				"title",			documentTitle,
				"date",				date,
				"time",				config.now(),
				"street",			reg.street,
				"city",				reg.city,
				"state",			reg.state,
				"zip",				reg.zip,
				"country",			reg.country,
				"sex",				reg.sex,
				"birth-date",			reg.birthdate,
				"uuid",				uuid,
				"uid1",				config.getUID(),
				"uid2",				config.getUID(),
				"uid3",				config.getUID(),
				"uid4",				config.getUID(),
				"pdf",				""	//This param must be last in the array (see *** below).
			};

	    System.out.println("metadataFolder: " + metadataFolder.toString());
		File temp = getTempDir(metadataFolder);
	    System.out.println("temp dir: " + temp.toString());

		File docEntrySource = new File(metadataFolder, "docEntrySource.xml");
	    System.out.println("docEntrySource: " + docEntrySource.toString());
		File docEntry = new File(temp,"docEntry.xml");
	    System.out.println("docEntry: " + docEntry.toString());
		File docEntrySourceToDocEntry = new File("docxsl/docEntrySourceToDocEntry.xsl");
	    System.out.println("docEntrySourceToDocEntry: " + docEntrySourceToDocEntry.toString());
	    System.out.println("check for error and return if so");

		if (!docEntrySourceToDocEntry.exists() || !docEntrySourceToDocEntry.isFile()) return;
		FileUtil.setText(
			docEntry,
			XmlUtil.toString(
				XmlUtil.getTransformedDocument(
					docEntrySource, docEntrySourceToDocEntry, params
				)
			)
		);
	    System.out.println("FileUtil.setFileText ...");

		File submissionSetSource = new File(metadataFolder,"submissionSetSource.xml");
	    System.out.println("submissionSetSource: " + submissionSetSource.toString());
		File submissionSet = new File(temp,"submissionSet.xml");
	    System.out.println("submissionSet: " + submissionSet.toString());
		File submissionSetSourceToSubmissionSet = new File("docxsl/submissionSetSourceToSubmissionSet.xsl");
	    System.out.println("submissionSetSourceToSubmissionSet: " + submissionSetSourceToSubmissionSet.toString());
		System.out.println("Submission set source XSL " + submissionSetSourceToSubmissionSet.getAbsolutePath());
		if (!submissionSetSourceToSubmissionSet.exists() || !submissionSetSourceToSubmissionSet.isFile()) return;
		FileUtil.setText(
			submissionSet,
			XmlUtil.toString(
				XmlUtil.getTransformedDocument(
					submissionSetSource, submissionSetSourceToSubmissionSet, params
				)
			)
		);
		String responseText = "transmission disabled";
		B_Source source = null;
//		if (sendsSOAP) {}
		{
			responseText = "XDS.b transmission attempted";
			System.out.println("Make a new txnData");
			String organizationalOID = config.getUIDRoot() + "10";
			SubmitTransactionData txnData = new SubmitTransactionData();
			System.out.println("make XDS document from payload: " + payload.getAbsolutePath());
				XDSDocument clinicalDocument = new XDSDocumentFromFile(
					documentDescriptor, payload);

			FileInputStream fis = new FileInputStream(docEntry);
			String docEntryUUID = txnData.loadDocumentWithMetadata(clinicalDocument, fis);
			fis.close();
			logger.debug("loadDocumentWithMetadata " + docEntryUUID);
			System.out.println("loadDocumentWithMetadata " + docEntryUUID);

			String uniqueID = OID.createOIDGivenRoot(organizationalOID, 64);
			System.out.println("Doc Entry UUID: " + uniqueID);
			txnData.getDocumentEntry(docEntryUUID).setUniqueId(uniqueID);
			logger.debug ("Done setting documentEntry metadata for " );

			fis = new FileInputStream(submissionSet);
			txnData.loadSubmissionSet(fis);
			fis.close();
			logger.debug("txtData.loadSubmissionSet");
			uniqueID = OID.createOIDGivenRoot(organizationalOID, 64);
			System.out.println("Submission Set Unique ID: " + uniqueID);
			txnData.getSubmissionSet().setUniqueId(uniqueID);
			txnData.getSubmissionSet().setSubmissionTime(Repository.formGMT_DTM());
//			txnData.saveMetadataToFile("/tmp/fail-meta-XDSb.xml");
			txnData.getSubmissionSet().setSourceId(organizationalOID);

			logger.debug("SOAP URL: " + soapURL);
			logger.debug("Make a new B_Source");
			source = new org.openhealthtools.ihe.xds.source.B_Source(
				new URI(soapURL));
			System.out.println("Submitting document " + soapURL);
			XDSResponseType response = null;
			response = source.submit(txnData);
			System.out.println("Doc submitted, check for errors");
			responseText = "XDS.b transmission completed";
			if (response == null) {
				//System.out.println("Null response back from submitting doc");
			} else if (response.getErrorList() == null) {
				//System.out.println("getErrorList is null");
			} else if (response.getErrorList().getError() == null) {
				//System.out.println("getErrorList.getError is null");
			} else {
				System.out.println("Returned " + response.getErrorList().getError().size() + " errors.");
				responseText = "XDS.b transmission encountered errors:";
				java.util.List<XDSErrorType> lst = response.getErrorList().getError();
				Iterator<XDSErrorType> it = lst.iterator();
				int ix = 1;
				while (it.hasNext()) {
					responseText += " (" + ix + ")" + it.next().getValue();
					ix++;
				}
				responseText += " (End of XDS.b xmit errors)";
			}
			//Finally, log an event.
			RegSysEvent event =
				new RegSysEvent(
						this,
						toStatus(responseText),
						RegSysEvent.TYPE_SOAP,
						"DocSet "+documentTitle+" processed.<br>"
							+"SOAP Response: "+StringUtil.displayable(responseText)
					);
			config.getEventLog().append(event);

			System.out.println("Done");

		}
	    }
	    catch (Exception ex) {
			//Get the top of the stack track
			StackTraceElement[] ste = ex.getStackTrace();
			String topElement = ste[0].toString();
			//Log an event
			RegSysEvent event =
				new RegSysEvent(
						this,
						RegSysEvent.STATUS_ERROR,
						RegSysEvent.TYPE_SOAP,
						"Unable to process XDSb submit request for " + reg.getFullName() +
						"<br>"+StringUtil.displayable(ex.getMessage())+
						"<br>"+StringUtil.displayable(topElement)
					);
			config.getEventLog().append(event);
			//logger.warn("Unable to process DocSet "+docSet.id,ex);
	    }
	}

	private synchronized File getTempDir(File dir) throws Exception {
		File temp = File.createTempFile("temp-","",dir);
		temp.delete();
		temp.mkdirs();
		return temp;
	}

	public static boolean isOK(String response) {
		return (response.toLowerCase().indexOf("status=\"success\"") != -1);
	}

	public static int toStatus(String response) {
		return  isOK(response) ? RegSysEvent.STATUS_OK : RegSysEvent.STATUS_ERROR;
	}

    //Convert an FO file to a PDF file using FOP
    private int convertFO2PDF(File fo, File pdf) throws Exception {

        OutputStream out = null;
    	FopFactory fopFactory = FopFactory.newInstance();

        try {
            FOUserAgent foUserAgent = fopFactory.newFOUserAgent();

            // Setup output stream.  Note: Using BufferedOutputStream
            // for performance reasons (helpful with FileOutputStreams).
            out = new FileOutputStream(pdf);
            out = new BufferedOutputStream(out);

            // Construct fop with desired output format
            Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, foUserAgent, out);

            // Setup JAXP using identity transformer
            TransformerFactory factory = TransformerFactory.newInstance();
            Transformer transformer = factory.newTransformer(); // identity transformer

            // Setup input stream
            Source src = new StreamSource(fo);

            // Resulting SAX events (the generated FO) must be piped through to FOP
            Result res = new SAXResult(fop.getDefaultHandler());

            // Start XSLT transformation and FOP processing
            transformer.transform(src, res);
            out.close();

            // Result processing
            FormattingResults foResults = fop.getResults();
            return foResults.getPageCount();
        }
        catch (Exception e) {
            if (out != null) out.close();
			throw e;
		}
    }

	public String getType() {
		return type;
	}

	public Element getXML() {
		try {
			Element e = super.getXML();
			e.setAttribute("globalAssigningAuthority", globalAssigningAuthority);
			e.setAttribute("soapURL", soapURL);
			e.setAttribute("sendsSOAP", yesNo(sendsSOAP));
			e.setAttribute("soapVersion", soapVersion);
			e.setAttribute("docsetDelay", Integer.toString(docsetDelay));
			return e;
		}
		catch (Exception ex) { return null; }
	}

	public static Element createNewElement(String name, String id) {
		try {
			Element e = Product.createNewElement(name, id);
			e.setAttribute("type", "Repository");
			e.setAttribute("soapVersion", "SOAP_1_2");
			e.setAttribute("sendsSOAP", "yes");
			e.setAttribute("docsetDelay", "10000");
			Configuration config = Configuration.getInstance();
			e.setAttribute("globalAssigningAuthority", config.getGlobalAssigningAuthority());
			e.setAttribute("globalAssigningAuthorityOID", config.getGlobalAssigningAuthorityOID());
			return e;
		}
		catch (Exception ex) { return null; }
	}
	public static String formTimeStamp() {
		long time = System.currentTimeMillis();
		GregorianCalendar c = new GregorianCalendar();
		c.setTimeInMillis(time);
		StringBuffer timeStamp = new StringBuffer();
		timeStamp.append(c.get(GregorianCalendar.YEAR));
		timeStamp.append("/");
		timeStamp.append(c.get(GregorianCalendar.MONTH) + 1);
		timeStamp.append("/");
		timeStamp.append( c.get(GregorianCalendar.DAY_OF_MONTH));
		timeStamp.append(" ");
		timeStamp.append( c.get(GregorianCalendar.HOUR_OF_DAY));
		timeStamp.append(":");
		timeStamp.append(c.get(GregorianCalendar.MINUTE));
		timeStamp.append(":");
		timeStamp.append(c.get(GregorianCalendar.SECOND));
		return timeStamp.toString();
	}
	public static String formGMT_DTM() {
		String timeInGMT = null;
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
		// first, set up time in current time zone (where program is running
		sdf.setTimeZone(TimeZone.getDefault());
		String tm = sdf.format(new Date());

		// convert (though there is probably is some circular logic here, oh well
		Date specifiedTime;
		//System.out.println("Specified time is: " + tm);
		//System.out.println("time zone is:GMT" + offset);
		try {
			// switch timezone
			specifiedTime = sdf.parse(tm);
			sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
			timeInGMT = sdf.format(specifiedTime);
			//System.out.println("Specified time post conversion: "+ tm);
			//System.exit(0);
		} catch (ParseException e) {
			// FIXME just skip the conversion, bad time stamp, hence bad
			// CDA!
			// Maybe this should be more robust?? An Exception?
		}
		return timeInGMT;
	}
}
