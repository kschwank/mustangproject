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
     * Generates an Extended profile invoice with two GROUP lines (different VAT rates: 7% and 19%),
     * each with two DETAIL child lines.  The output is written to
     * {@code ./target/testout-ZF2HierarchicalMixedVAT.xml} and
     * {@code ./target/testout-ZF2HierarchicalMixedVAT.pdf} so that external validators
     * (e.g. Mustang-CLI, Konik, VeraPDF) and visualisation tools can inspect the result.
     */
    @Test
    void testHierarchicalMixedVATOutputFiles() throws IOException, ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        // --- seller ---
        TradeParty sender = new TradeParty("Bei Spiel GmbH", "Ecke 12", "12345", "Stadthausen", "DE")
                .addVATID("DE136695976")
                .addBankDetails(new BankDetails("DE88200800000970375700", "COBADEFFXXX"));

        // --- buyer ---
        TradeParty recipient = new TradeParty("Theodor Est", "Bahnstr. 42", "88802", "Spielkreis", "DE")
                .setContact(new Contact("Ingmar N. Fo", "+49555237823", "info@localhost.local"));

        // ===== Group 1 – Books (7 % VAT) =====
        // Parent GROUP line (zero price, carries the section heading)
        Item group1 = new Item(
                new Product("Bücher-Paket", "Buchpaket Frühjahr 2026", "C62", new BigDecimal("7")),
                BigDecimal.ZERO, BigDecimal.ONE)
                .setId("1")
                .setLineStatusReasonCode("GROUP");

        // Child 1.1 – single book, 7 %
        Item detail1_1 = new Item(
                new Product("Fachbuch Java", "ISBN 978-3-86490-111-1", "C62", new BigDecimal("7")),
                new BigDecimal("39.90"), new BigDecimal("2"))
                .setId("2")
                .setParentLineID("1")
                .setLineStatusReasonCode("DETAIL");

        // Child 1.2 – second book, 7 %
        Item detail1_2 = new Item(
                new Product("Fachbuch XML", "ISBN 978-3-86490-222-2", "C62", new BigDecimal("7")),
                new BigDecimal("29.90"), new BigDecimal("1"))
                .setId("3")
                .setParentLineID("1")
                .setLineStatusReasonCode("DETAIL");

        // ===== Group 2 – Electronics (19 % VAT) =====
        Item group2 = new Item(
                new Product("Elektronik-Paket", "Hardware und Zubehör", "C62", new BigDecimal("19")),
                BigDecimal.ZERO, BigDecimal.ONE)
                .setId("4")
                .setLineStatusReasonCode("GROUP");

        // Child 2.1 – USB hub, 19 %
        Item detail2_1 = new Item(
                new Product("USB-Hub 4-Port", "USB 3.0, aktiv", "C62", new BigDecimal("19")),
                new BigDecimal("24.99"), new BigDecimal("3"))
                .setId("5")
                .setParentLineID("4")
                .setLineStatusReasonCode("DETAIL");

        // Child 2.2 – HDMI cable, 19 %
        Item detail2_2 = new Item(
                new Product("HDMI-Kabel 2m", "4K, vergoldet", "C62", new BigDecimal("19")),
                new BigDecimal("9.99"), new BigDecimal("5"))
                .setId("6")
                .setParentLineID("4")
                .setLineStatusReasonCode("DETAIL");

        Invoice invoice = new Invoice()
                .setNumber("INV-2026-HIER-MIXED-001")
                .setIssueDate(sdf.parse("2026-03-09"))
                .setDeliveryDate(sdf.parse("2026-03-07"))
                .setDueDate(sdf.parse("2026-04-09"))
                .setSender(sender)
                .setRecipient(recipient)
                .setCurrency("EUR")
                .addItem(group1)
                .addItem(detail1_1)
                .addItem(detail1_2)
                .addItem(group2)
                .addItem(detail2_1)
                .addItem(detail2_2);

        // --- write XML ---
        final String TARGET_XML = "./target/testout-ZF2HierarchicalMixedVAT.xml";
        final String TARGET_PDF = "./target/testout-ZF2HierarchicalMixedVAT.pdf";

        ZUGFeRD2PullProvider provider = new ZUGFeRD2PullProvider();
        provider.setProfile(Profiles.getByName("Extended"));
        provider.generateXML(invoice);
        byte[] xmlBytes = provider.getXML();
        String xml = new String(xmlBytes, StandardCharsets.UTF_8);

        Files.write(Paths.get(TARGET_XML), xmlBytes);

        // --- basic content assertions on the XML ---
        assertTrue(xml.contains("<ram:LineID>1</ram:LineID>"), "Group 1 LineID");
        assertTrue(xml.contains("<ram:LineID>4</ram:LineID>"), "Group 2 LineID");
        assertTrue(xml.contains("<ram:ParentLineID>1</ram:ParentLineID>"), "Children of group 1 reference parent");
        assertTrue(xml.contains("<ram:ParentLineID>4</ram:ParentLineID>"), "Children of group 2 reference parent");
        // Both VAT rates must appear
        assertTrue(xml.contains(">7.00<") || xml.contains(">7<"), "7% VAT rate in XML");
        assertTrue(xml.contains(">19.00<") || xml.contains(">19<"), "19% VAT rate in XML");
        // GROUP and DETAIL markers
        assertTrue(xml.contains("<ram:LineStatusReasonCode>GROUP</ram:LineStatusReasonCode>"), "GROUP code");
        assertTrue(xml.contains("<ram:LineStatusReasonCode>DETAIL</ram:LineStatusReasonCode>"), "DETAIL code");

        // --- write PDF with embedded XML ---
        InputStream sourcePdf = getClass().getResourceAsStream(
                "/MustangGnuaccountingBeispielRE-20201121_508blanko.pdf");
        assertNotNull(sourcePdf, "Blank source PDF must exist in test resources");

        try (ZUGFeRDExporterFromA1 ze = new ZUGFeRDExporterFromA1()) {
            ze.ignorePDFAErrors();
            ze.load(sourcePdf);
            ze.setProducer("mustangproject HierarchicalPositionsTest")
              .setCreator("mustangproject")
              .setZUGFeRDVersion(2)
              .setProfile(Profiles.getByName("Extended"));
            ze.setTransaction(invoice);
            ze.export(TARGET_PDF);
        }

        // --- verify the PDF was created and contains the expected XML snippet ---
        ZUGFeRDImporter zi = new ZUGFeRDImporter(TARGET_PDF);
        String embeddedXml = zi.getUTF8();
        assertTrue(embeddedXml.contains("<ram:ParentLineID>1</ram:ParentLineID>"),
                "Embedded XML in PDF must contain ParentLineID reference to group 1");
        assertTrue(embeddedXml.contains("<ram:ParentLineID>4</ram:ParentLineID>"),
                "Embedded XML in PDF must contain ParentLineID reference to group 2");
        assertTrue(embeddedXml.contains("<ram:LineStatusReasonCode>GROUP</ram:LineStatusReasonCode>"),
                "Embedded XML in PDF must contain GROUP marker");
        assertTrue(embeddedXml.contains("<rsm:CrossIndustryInvoice"),
                "Embedded XML must be a CII document");
    }
}
