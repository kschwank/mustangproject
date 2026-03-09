package org.mustangproject.ZUGFeRD;

import org.junit.jupiter.api.Test;
import org.mustangproject.BankDetails;
import org.mustangproject.Contact;
import org.mustangproject.Invoice;
import org.mustangproject.Item;
import org.mustangproject.Product;
import org.mustangproject.TradeParty;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class HierarchicalPositionsTest {

    /** Build a valid Extended profile invoice with a GROUP parent and a DETAIL child line. */
    private Invoice buildHierarchicalInvoice() {
        Product parentProduct = new Product("Parent Group", "", "C62", new BigDecimal("19"));
        Item parentItem = new Item(parentProduct, new BigDecimal("0.00"), new BigDecimal("1"))
                .setId("1")
                .setLineStatusReasonCode("GROUP");

        Product childProduct = new Product("Child Detail", "", "C62", new BigDecimal("19"));
        Item childItem = new Item(childProduct, new BigDecimal("100.00"), new BigDecimal("2"))
                .setId("2")
                .setParentLineID("1")
                .setLineStatusReasonCode("DETAIL");

        TradeParty sender = new TradeParty("Test Seller", "Seller Street 1", "12345", "Berlin", "DE");
        TradeParty recipient = new TradeParty("Test Buyer", "Buyer Street 2", "54321", "Munich", "DE");

        return new Invoice()
                .setNumber("INV-2026-HIER-001")
                .setIssueDate(new Date())
                .setDeliveryDate(new Date())
                .setDueDate(new Date())
                .setSender(sender)
                .setRecipient(recipient)
                .setCurrency("EUR")
                .addItem(parentItem)
                .addItem(childItem);
    }

    @Test
    void testHierarchicalPositionsExtendedProfile() {
        ZUGFeRD2PullProvider provider = new ZUGFeRD2PullProvider();
        provider.setProfile(Profiles.getByName("Extended"));
        provider.generateXML(buildHierarchicalInvoice());
        String xml = new String(provider.getXML());

        // Verify LineID, ParentLineID and LineStatusReasonCode (GROUP/DETAIL per ZUGFeRD spec)
        assertTrue(xml.contains("<ram:LineID>1</ram:LineID>"), "Parent LineID should be present");
        assertTrue(xml.contains("<ram:LineID>2</ram:LineID>"), "Child LineID should be present");
        assertTrue(xml.contains("<ram:ParentLineID>1</ram:ParentLineID>"), "Child should reference parent");
        assertTrue(xml.contains("<ram:LineStatusReasonCode>GROUP</ram:LineStatusReasonCode>"), "GROUP code should be present");
        assertTrue(xml.contains("<ram:LineStatusReasonCode>DETAIL</ram:LineStatusReasonCode>"), "DETAIL code should be present");
        // LineStatusCode (the separate XSD element) should NOT be present (not set)
        assertFalse(xml.contains("<ram:LineStatusCode>"), "LineStatusCode should not be present when not set");
    }

    @Test
    void testHierarchicalPositionsXsdValid() throws IOException, SAXException {
        ZUGFeRD2PullProvider provider = new ZUGFeRD2PullProvider();
        provider.setProfile(Profiles.getByName("Extended"));
        provider.generateXML(buildHierarchicalInvoice());
        byte[] xml = provider.getXML();

        // Locate the Extended XSD relative to the library module working directory
        File xsdFile = new File("../validator/target/classes/schema/ZF_240/EXTENDED/FACTUR-X_EXTENDED.xsd");
        if (!xsdFile.exists()) {
            xsdFile = new File("../validator/src/main/resources/schema/ZF_240/EXTENDED/FACTUR-X_EXTENDED.xsd");
        }
        // Skip gracefully if the XSD is not available (e.g. standalone library build)
        org.junit.jupiter.api.Assumptions.assumeTrue(xsdFile.exists(),
                "Extended XSD not found, skipping schema validation: " + xsdFile.getAbsolutePath());

        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Validator validator = sf.newSchema(xsdFile).newValidator();
        // Should not throw a SAXException if the XML is schema-valid
        validator.validate(new StreamSource(new ByteArrayInputStream(xml)));
    }

    @Test
    void testHierarchicalPositionsRoundTrip() throws IOException, ParseException,
            javax.xml.xpath.XPathExpressionException {
        ZUGFeRD2PullProvider provider = new ZUGFeRD2PullProvider();
        provider.setProfile(Profiles.getByName("Extended"));
        provider.generateXML(buildHierarchicalInvoice());
        byte[] xml = provider.getXML();

        // Parse back
        ZUGFeRDInvoiceImporter importer = new ZUGFeRDInvoiceImporter();
        importer.setRawXML(xml, false);
        Invoice parsed = new Invoice();
        importer.extractInto(parsed);

        IZUGFeRDExportableItem[] items = parsed.getZFItems();
        assertNotNull(items, "Parsed invoice should have items");
        assertEquals(2, items.length, "Should have exactly 2 items");

        // Item at index 0 is the GROUP parent
        assertEquals("1", items[0].getId(), "Parent item ID should be '1'");
        assertEquals("GROUP", items[0].getLineStatusReasonCode(), "Parent LineStatusReasonCode should be GROUP");
        assertNull(items[0].getLineStatusCode(), "Parent LineStatusCode (separate XSD element) should be null");
        assertNull(items[0].getParentLineID(), "Parent item should have no ParentLineID");

        // Item at index 1 is the DETAIL child
        assertEquals("2", items[1].getId(), "Child item ID should be '2'");
        assertEquals("DETAIL", items[1].getLineStatusReasonCode(), "Child LineStatusReasonCode should be DETAIL");
        assertNull(items[1].getLineStatusCode(), "Child LineStatusCode (separate XSD element) should be null");
        assertEquals("1", items[1].getParentLineID(), "Child should reference parent line '1'");
    }

    /**
     * Generates an Extended profile invoice modelling a European rail journey
     * with two trips and hierarchical positions. This is a German invoice
     * issued by EuroRail Services GmbH. The foreign rail segments (France, Belgium)
     * are not subject to German VAT because the tax is settled by the foreign
     * train operators (SNCF, SNCB) in their respective countries.
     * <pre>
     *   1  GROUP  Frankfurt Hbf to Paris Est (entire trip)
     *   2  DETAIL   Fahrkarte DE-Abschnitt Frankfurt to Saarbruecken      (19 % DE VAT, category S)
     *   3  DETAIL   Sitzplatzreservierung DE-Abschnitt                    (19 % DE VAT, category S)
     *   4  DETAIL   Billet FR-troncon Forbach to Paris Est                 (not subject to DE VAT, category O)
     *   5  DETAIL   Reservation siege FR-troncon                          (not subject to DE VAT, category O)
     *   6  GROUP  Paris Nord to Bruxelles-Midi (entire trip)
     *   7  DETAIL   Ticket BE-traject Paris Nord to Bruxelles-Midi        (not subject to DE VAT, category O)
     *   8  DETAIL   Zitplaatsreservering BE-traject                       (not subject to DE VAT, category O)
     * </pre>
     *
     * Output files (for external validators and visualisation tools):
     * <ul>
     *   <li>{@code ./target/testout-ZF2HierarchicalTrainTicket.xml} - standalone CII XML</li>
     *   <li>{@code ./target/testout-ZF2HierarchicalTrainTicket.pdf} - PDF rendered from XML
     *       via {@link ZUGFeRDVisualizer} so that visual content and data are consistent</li>
     * </ul>
     */
    @Test
    void testHierarchicalTrainTicketOutputFiles() throws Exception {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        // --- Seller: German rail operator ---
        TradeParty sender = new TradeParty(
                "EuroRail Services GmbH", "Bahnhofsplatz 1", "60329", "Frankfurt am Main", "DE")
                .addVATID("DE812526315")
                .addBankDetails(new BankDetails("DE89370400440532013000", "COBADEFFXXX"));

        // --- Buyer: business traveller ---
        TradeParty recipient = new TradeParty(
                "Sophie Beismann", "Rue de la Loi 42", "1040", "Bruxelles", "BE")
                .setContact(new Contact("Sophie Beismann", "+32-2-555-0199", "sophie.beismann@example.com"));

        // =====================================================================
        // Trip 1: Frankfurt Hbf -> Paris Est  (GROUP line 1)
        //   DE segment: 19% German VAT (standard rate)
        //   FR segment: not subject to German VAT (tax paid by SNCF)
        // =====================================================================
        Item trip1 = new Item(
                new Product("Fahrt Frankfurt - Paris",
                        "Frankfurt Hbf - Paris Est, ICE/TGV", "C62", new BigDecimal("19")),
                BigDecimal.ZERO, BigDecimal.ONE)
                .setId("1")
                .setLineStatusReasonCode("GROUP");

        // DE leg - ticket (19% VAT)
        Item trip1_deTicket = new Item(
                new Product("Fahrkarte 2. Klasse DE-Abschnitt",
                        "Frankfurt Hbf - Saarbruecken Hbf, ICE 9551", "C62", new BigDecimal("19")),
                new BigDecimal("47.50"), BigDecimal.ONE)
                .setId("2")
                .setParentLineID("1")
                .setLineStatusReasonCode("DETAIL");

        // DE leg - seat reservation (19% VAT)
        Item trip1_deSeat = new Item(
                new Product("Sitzplatzreservierung DE-Abschnitt",
                        "Wagen 26 Platz 53, Fenster", "C62", new BigDecimal("19")),
                new BigDecimal("4.50"), BigDecimal.ONE)
                .setId("3")
                .setParentLineID("1")
                .setLineStatusReasonCode("DETAIL");

        // FR leg - ticket (not subject to German VAT, tax paid by SNCF)
        Product frTicketProduct = new Product("Billet 2eme classe troncon FR",
                "Forbach - Paris Est, TGV 9552", "C62", BigDecimal.ZERO);
        frTicketProduct.setTaxCategoryCode("O");
        frTicketProduct.setTaxExemptionReason("Steuer wird durch SNCF im Ausland abgefuehrt");
        Item trip1_frTicket = new Item(frTicketProduct, new BigDecimal("62.00"), BigDecimal.ONE)
                .setId("4")
                .setParentLineID("1")
                .setLineStatusReasonCode("DETAIL");

        // FR leg - seat reservation (not subject to German VAT)
        Product frSeatProduct = new Product("Reservation siege troncon FR",
                "Voiture 4 Place 28", "C62", BigDecimal.ZERO);
        frSeatProduct.setTaxCategoryCode("O");
        frSeatProduct.setTaxExemptionReason("Steuer wird durch SNCF im Ausland abgefuehrt");
        Item trip1_frSeat = new Item(frSeatProduct, new BigDecimal("3.00"), BigDecimal.ONE)
                .setId("5")
                .setParentLineID("1")
                .setLineStatusReasonCode("DETAIL");

        // =====================================================================
        // Trip 2: Paris Nord -> Bruxelles-Midi  (GROUP line 6)
        //   entirely outside Germany: not subject to German VAT (tax paid by SNCB)
        // =====================================================================
        Product trip2Product = new Product("Fahrt Paris - Bruxelles",
                "Paris Nord - Bruxelles-Midi, Thalys 9364", "C62", BigDecimal.ZERO);
        trip2Product.setTaxCategoryCode("O");
        trip2Product.setTaxExemptionReason("Steuer wird durch SNCB im Ausland abgefuehrt");
        Item trip2 = new Item(trip2Product, BigDecimal.ZERO, BigDecimal.ONE)
                .setId("6")
                .setLineStatusReasonCode("GROUP");

        // BE leg - ticket (not subject to German VAT)
        Product beTicketProduct = new Product("Ticket 2de klasse BE-traject",
                "Paris Nord - Bruxelles-Midi, Thalys 9364", "C62", BigDecimal.ZERO);
        beTicketProduct.setTaxCategoryCode("O");
        beTicketProduct.setTaxExemptionReason("Steuer wird durch SNCB im Ausland abgefuehrt");
        Item trip2_beTicket = new Item(beTicketProduct, new BigDecimal("55.00"), BigDecimal.ONE)
                .setId("7")
                .setParentLineID("6")
                .setLineStatusReasonCode("DETAIL");

        // BE leg - seat reservation (not subject to German VAT)
        Product beSeatProduct = new Product("Zitplaatsreservering BE-traject",
                "Rijtuig 12 Plaats 7", "C62", BigDecimal.ZERO);
        beSeatProduct.setTaxCategoryCode("O");
        beSeatProduct.setTaxExemptionReason("Steuer wird durch SNCB im Ausland abgefuehrt");
        Item trip2_beSeat = new Item(beSeatProduct, new BigDecimal("4.00"), BigDecimal.ONE)
                .setId("8")
                .setParentLineID("6")
                .setLineStatusReasonCode("DETAIL");

        // --- Build the invoice ---
        Invoice invoice = new Invoice()
                .setNumber("RAIL-2026-EU-00417")
                .setIssueDate(sdf.parse("2026-03-09"))
                .setDeliveryDate(sdf.parse("2026-03-15"))
                .setDueDate(sdf.parse("2026-04-09"))
                .setSender(sender)
                .setRecipient(recipient)
                .setCurrency("EUR")
                .addItem(trip1)
                .addItem(trip1_deTicket)
                .addItem(trip1_deSeat)
                .addItem(trip1_frTicket)
                .addItem(trip1_frSeat)
                .addItem(trip2)
                .addItem(trip2_beTicket)
                .addItem(trip2_beSeat);

        // --- Generate standalone XML ---
        final String TARGET_XML = "./target/testout-ZF2HierarchicalTrainTicket.xml";
        final String TARGET_PDF = "./target/testout-ZF2HierarchicalTrainTicket.pdf";

        ZUGFeRD2PullProvider provider = new ZUGFeRD2PullProvider();
        provider.setProfile(Profiles.getByName("Extended"));
        provider.generateXML(invoice);
        byte[] xmlBytes = provider.getXML();
        String xml = new String(xmlBytes, StandardCharsets.UTF_8);

        Files.write(Paths.get(TARGET_XML), xmlBytes);

        // --- Assertions on hierarchical structure ---
        // Two GROUP parents (trip 1 = line 1, trip 2 = line 6)
        assertTrue(xml.contains("<ram:LineID>1</ram:LineID>"), "Trip 1 group LineID");
        assertTrue(xml.contains("<ram:LineID>6</ram:LineID>"), "Trip 2 group LineID");
        // Trip 1 children (lines 2-5) all reference parent 1
        assertTrue(xml.contains("<ram:ParentLineID>1</ram:ParentLineID>"), "Trip 1 children reference parent");
        // Trip 2 children (lines 7-8) reference parent 6
        assertTrue(xml.contains("<ram:ParentLineID>6</ram:ParentLineID>"), "Trip 2 children reference parent");
        // GROUP and DETAIL markers
        assertTrue(xml.contains("<ram:LineStatusReasonCode>GROUP</ram:LineStatusReasonCode>"), "GROUP code");
        assertTrue(xml.contains("<ram:LineStatusReasonCode>DETAIL</ram:LineStatusReasonCode>"), "DETAIL code");
        // DE segments: 19% standard rate
        assertTrue(xml.contains(">19.00<") || xml.contains(">19<"), "19% DE VAT rate");
        // FR and BE segments: not subject to tax (category O), exemption reason present
        assertTrue(xml.contains("<ram:CategoryCode>O</ram:CategoryCode>"), "Category O for non-DE segments");
        assertTrue(xml.contains("<ram:ExemptionReason>Steuer wird durch SNCF im Ausland abgefuehrt</ram:ExemptionReason>"),
                "FR exemption reason");
        assertTrue(xml.contains("<ram:ExemptionReason>Steuer wird durch SNCB im Ausland abgefuehrt</ram:ExemptionReason>"),
                "BE exemption reason");
        // Standard rate also present for DE segments
        assertTrue(xml.contains("<ram:CategoryCode>S</ram:CategoryCode>"), "Category S for DE segments");

        // --- Generate a visualisation PDF from the XML (content matches!) ---
        ZUGFeRDVisualizer visualizer = new ZUGFeRDVisualizer();
        byte[] pdfBytes = visualizer.toPDF(xml);
        assertNotNull(pdfBytes, "Visualisation PDF bytes must not be null");
        assertTrue(pdfBytes.length > 0, "Visualisation PDF must not be empty");
        Files.write(Paths.get(TARGET_PDF), pdfBytes);
    }
}
