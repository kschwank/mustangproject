package org.mustangproject.ZUGFeRD;

import org.junit.jupiter.api.Test;
import org.mustangproject.Invoice;
import org.mustangproject.Item;
import org.mustangproject.Product;
import org.mustangproject.TradeParty;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
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
}
