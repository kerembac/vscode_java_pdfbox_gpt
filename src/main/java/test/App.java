package test;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;

public class App {
    public static void main(String[] args) throws Exception {
        String pdfPath = args.length > 0 ? args[0] : "input.pdf";

        try (PDDocument doc = Loader.loadPDF(new File(pdfPath))) {
PDFTextStripper stripper = new PDFTextStripper();
stripper.setSortByPosition(false);
stripper.setLineSeparator("\n");
stripper.setParagraphStart("\n");
stripper.setParagraphEnd("\n\n");
int maxPages = 2; // change as needed
stripper.setStartPage(0);
stripper.setEndPage(Math.min(maxPages, doc.getNumberOfPages()));

String text = stripper.getText(doc);
System.out.println(text);

        }
    }
}
