/* The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * https://faban.dev.java.net/public/CDDLv1.0.html or
 * install_dir/license.txt
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at faban/src/legal/CDDLv1.0.txt.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: FabanHTTPBench.java,v 1.1 2007/06/29 08:35:17 akara Exp $
 *
 * Copyright 2005 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.faban.driver.util;

import com.sun.faban.driver.core.RunInfo;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.StringTokenizer;

/**
 * This is the "common driver" for simple HTTP benchmarking. It allows a
 * user to setup a single command-line that will create the appropriate
 * run.xml file, run the HTTP driver, and print out the ops/sec and other
 * data from the summary.xml: e.g
 *
 * <pre>
 * %java [jvmargs] com.sun.faban.driver.util.FabanHTTPBench -c 500 -W 500 -s http://localhost:8080/Ping/PingServlet
 * ops/sec: 7169.300
 * % errors: 0.0
 * avg. time: 0.219
 * max time: 2.861
 * 90th %: 0.32
 * </pre>
 *
 * The above command runs 500 simlutaneous connections to PingServlet, which
 * served 7169 operations per second with an average time of 0.21 seconds.
 *
 * @author Scott Oaks
 */
public class FabanHTTPBench {

    // TODO: For real scaling, we need multiple agents on multiple machines.
    // Faban handles that quite well, but can we make a standalone program for
    // that? For more sophisticated (i.e., *real*) benchmarking, people should
    // just use Faban directly.

    private static int numThreads = 1;
    private static String rampUp = "300";
    private static String rampDown = "120";
    private static String steadyState = "300";
    private static double ninetyPct = 1.0;
    private static boolean postRequest = false;
    private static String path;
    private static String queryString;
    private static String outputDirectory;
    private static String runXmlFileName;
    private static boolean save = false;
    private static boolean substitute = false;
    private static boolean isBinary = false;
    private static String thinkTime = "0";
    private static final int CYCLE_DEVIATION = 1;

    public static void main(String[] args) throws Exception {
        outputDirectory = System.getProperty("java.io.tmpdir");
        if (!outputDirectory.endsWith(File.separator))
            outputDirectory += File.separatorChar;
        outputDirectory += "fhb";

        parseArgs(args);
        makeRunXml();
        run();
        reportResults();
        cleanUp();
        System.exit(0);
    }

    private static void makeRunXml()
            throws ParserConfigurationException,
            TransformerConfigurationException,
            TransformerException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();
        Element root = doc.createElementNS(RunInfo.FABANURI, "runConfig");
        root.setPrefix("");
        Element control = doc.createElementNS(RunInfo.FABANURI, "runControl");
        control.setPrefix("");
        control.setAttributeNS(null, "unit", "time");
        Element tmp = doc.createElementNS(RunInfo.FABANURI, "rampUp");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode(rampUp));
        control.appendChild(tmp);
        tmp = doc.createElementNS(RunInfo.FABANURI, "steadyState");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode(steadyState));
        control.appendChild(tmp);
        tmp = doc.createElementNS(RunInfo.FABANURI, "rampDown");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode(rampDown));
        control.appendChild(tmp);
        root.appendChild(control);

        Element def = doc.createElementNS(RunInfo.DRIVERURI,
                                                    "benchmarkDefinition");
        def.setPrefix("");
        tmp = doc.createElementNS(RunInfo.DRIVERURI,  "name");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode("fhb"));
        def.appendChild(tmp);
        tmp = doc.createElementNS(RunInfo.DRIVERURI,  "metric");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode("ops/sec"));
        def.appendChild(tmp);
        root.appendChild(def);

        Element out = doc.createElementNS(RunInfo.DRIVERURI, "outputDir");
        out.setPrefix("");
        out.appendChild(doc.createTextNode(outputDirectory));
        root.appendChild(out);

        Element ts = doc.createElementNS(RunInfo.DRIVERURI, "threadStart");
        ts.setPrefix("");
        tmp = doc.createElementNS(RunInfo.DRIVERURI, "delay");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode("20"));
        ts.appendChild(tmp);
        root.appendChild(ts);

        Element dc = doc.createElementNS(RunInfo.DRIVERURI, "driverConfig");
        dc.setPrefix("");
        dc.setAttributeNS(null, "name", "http_driver1");
        tmp = doc.createElementNS(RunInfo.DRIVERURI, "threads");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode("" + numThreads));
        dc.appendChild(tmp);
        Element rlt = doc.createElementNS(RunInfo.DRIVERURI, "requestLagTime");
        rlt.setPrefix("");
        Element u = doc.createElementNS(RunInfo.DRIVERURI, "uniform");
        u.setPrefix("");
        tmp = doc.createElementNS(RunInfo.DRIVERURI, "cycleType");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode("thinktime"));
        u.appendChild(tmp);
        tmp = doc.createElementNS(RunInfo.DRIVERURI, "cycleMin");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode(thinkTime));
        u.appendChild(tmp);
        tmp = doc.createElementNS(RunInfo.DRIVERURI, "cycleMax");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode(thinkTime));
        u.appendChild(tmp);
        tmp = doc.createElementNS(RunInfo.DRIVERURI, "cycleDeviation");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode("" + CYCLE_DEVIATION));
        u.appendChild(tmp);
        rlt.appendChild(u);
        dc.appendChild(rlt);
        Element op = doc.createElementNS(RunInfo.DRIVERURI, "operation");
        op.setPrefix("");
        tmp = doc.createElementNS(RunInfo.DRIVERURI, "name");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode("test"));
        op.appendChild(tmp);
        tmp = doc.createElementNS(RunInfo.DRIVERURI, "url");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode(path));
        op.appendChild(tmp);
        if (postRequest) {
            tmp = doc.createElementNS(RunInfo.DRIVERURI, "post");
            tmp.setAttributeNS(null, "binary", Boolean.toString(isBinary));
            tmp.setAttributeNS(null, "file", "true");
        } else {
            tmp = doc.createElementNS(RunInfo.DRIVERURI, "get");
        }
        tmp.setPrefix("");
        tmp.setAttributeNS(null, "subst", Boolean.toString(substitute));
        tmp.appendChild(doc.createCDATASection(queryString));
        op.appendChild(tmp);
        tmp = doc.createElementNS(RunInfo.DRIVERURI, "max90th");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode("" + ninetyPct));
        op.appendChild(tmp);
        dc.appendChild(op);
        Element om = doc.createElementNS(RunInfo.DRIVERURI, "operationmix");
        om.setPrefix("");
        tmp = doc.createElementNS(RunInfo.DRIVERURI, "name");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode("test"));
        om.appendChild(tmp);
        tmp = doc.createElementNS(RunInfo.DRIVERURI, "r");
        tmp.setPrefix("");
        tmp.appendChild(doc.createTextNode("1"));
        om.appendChild(tmp);
        dc.appendChild(om);
        root.appendChild(dc);
        doc.appendChild(root);

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        Source src = new DOMSource(doc);
        File f = new File(outputDirectory, "run.xml");
        runXmlFileName = f.getPath();
        Result dest = new StreamResult(f);
        t.transform(src, dest);
    }

    private static void run() throws NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        System.setProperty("benchmark.config", runXmlFileName);
        System.setProperty("faban.sequence.path", outputDirectory);
        Class c = com.sun.faban.driver.core.MasterImpl.class;
        Class[] arg = new Class[1];
        arg[0] = String[].class;
        Method m = c.getMethod("main", arg);
        Object[] args = new Object[1];
        String[] s = new String[1];
        s[0] = "-noexit";
        args[0] = s;
        m.invoke(null, args);
    }

    private static void reportResults() throws IOException,
            ParserConfigurationException, SAXException {
        BufferedReader fr =
                new BufferedReader(new FileReader(
                        new File(outputDirectory, "fhb.seq")));
        String s = fr.readLine();
        fr.close();
        Integer seq = Integer.parseInt(s) - 1;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        File f = new File(outputDirectory +
                System.getProperty("file.separator") +
                seq, "summary.xml");
        Document doc = factory.newDocumentBuilder().parse(f);
        System.out.println("ops/sec: " + getValue(doc, "metric"));
        int successes = Integer.parseInt(getValue(doc, "successes"));
        int fails = Integer.parseInt(getValue(doc, "failures"));
        int total = successes + fails;
        double errors =
                (1 - ((((double) total - fails) / (double) total))) * 100.;
        System.out.println("% errors: " + errors);
        System.out.println("avg. time: " + getValue(doc, "avg"));
        System.out.println("max time: " + getValue(doc, "max"));
        String p90th = getValue(doc, "p90th");
        System.out.println("90th %: " + p90th);
        if (Double.parseDouble(p90th) > ninetyPct)
            System.out.println("ERROR: Missed target 90% of " + ninetyPct);
        int users = Integer.parseInt(getValue(doc, "users"));
        double rt = Double.parseDouble(getValue(doc, "rtXtps"));
        if (users * .975 > rt)
            System.out.println("WARNING: Little's law verification results " +
                    "low: " + users + " users requested; " + rt +
                    " users simulated");
        double ta = Double.parseDouble(getValue(doc, "targetedAvg"));
        double aa = Double.parseDouble(getValue(doc, "actualAvg"));
        if (Math.abs(aa - ta)/ta > (CYCLE_DEVIATION / 100d))
            System.out.println("ERROR: Think time deviation is too high; " +
                    "requested " + thinkTime + "; actual is " + (aa * 1000));
    }

    private static String getValue(Document doc, String s) {
        return doc.getElementsByTagName(s).item(0).getChildNodes().
                item(0).getNodeValue().trim();
    }

    private static void cleanUp() {
        if (save) {
            System.out.println("Saving output from run in " + outputDirectory);
            return;
        }
        if (!deleteDirectory(new File(outputDirectory))) {
            System.err.println("WARNING: Can't remove all files from " +
                                                            outputDirectory);
        }
    }

    private static boolean deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (int i = 0; i < children.length; i++) {
                if (!deleteDirectory(new File(dir, children[i])))
                    return false;
            }
        }
        return dir.delete();
    }

    private static void parseArgs(String[] args) throws MalformedURLException {
        int i;
        String optArg = null;
        for (i = 0; i < args.length; i++) {
            char[] c = args[i].toCharArray();
            if (c[0] != '-')
                break;
            switch(c[1]) {
                case 'D':
                    outputDirectory =
                            c.length > 2 ? args[i].substring(2) : args[++i];
                    break;
                case 'n':
                    System.err.println("numRequests not supported");
                    System.err.println("Please specify rampup/steady times " +
                                                                    "instead");
                    System.exit(-1);
                case 'r':
                    optArg = c.length > 2 ? args[i].substring(2) : args[++i];
                    StringTokenizer tok = new StringTokenizer(optArg, "/");
                    rampUp = tok.nextToken();
                    steadyState = tok.nextToken();
                    rampDown = tok.nextToken();
                    break;
                case 'W':
                    thinkTime = c.length > 2 ? args[i].substring(2) : args[++i];
                    break;
                case 's': save = true; break;
                case 'S': substitute = true; break;
                case 'c':
                    optArg = c.length > 2 ? args[i].substring(2) : args[++i];
                    numThreads = Integer.parseInt(optArg); break;
                case 't':
                    optArg = c.length > 2 ? args[i].substring(2) : args[++i];
                    ninetyPct = Double.parseDouble(optArg); break;
                case 'p':
                    queryString =
                            c.length > 2 ? args[i].substring(2) : args[++i];
                    postRequest = true; break;
                case 'b': isBinary = true; break;
                case 'k':
                    System.err.println("Warning: keep alive is always on");
                    break;
                case 'V':
                    System.out.println("Faban cd: Version 0.1");
                    System.exit(0);
                case 'h':
                default:
                    usage();
                    break;
            }
        }
        if (i == args.length)
            usage();
        URL u = new URL(args[i]);
        String proto = u.getProtocol();
        if (!proto.equals("http") && !(proto.equals("https")))
            throw new IllegalArgumentException("Unsupported protocol " + proto);
        path = proto + "://" + u.getAuthority() + u.getPath();
        if (!postRequest) {
            String q = u.getQuery();
            if (q == null)
                queryString = "";
            else queryString = "?" + q;
        }
        File f = new File(outputDirectory);
        if (!f.exists() && !f.mkdirs())
            throw new IllegalArgumentException("Can't create temp directory " +
                    outputDirectory);
    }

    private static void usage() {
        String cmd = System.getProperty("faban.cli.command");
        if (cmd == null) {
            System.err.println("usage: java [jvm options] com.sun.faban." +
                            "driver.util.FabanHTTPBench [program options] URL");
            System.err.println("Use standard options (including -server) for " +
                                        "jvm options");
        } else {
            System.err.println("usage: " + cmd + " [program options] URL");
        }
        System.err.println("Suported program options are: ");
        if (cmd != null) {
            System.err.println("\t-J jvm_option : Set particular JVM option");
            System.err.println("\t\tUse standard options (including -server) " +
                                                            "for jvm options");
        }
        System.err.println("\t-D directory : Use directory for temporary files");
        System.err.println("\t-r rampup/steady/rampDown :");
        System.err.println("\t\tRun for given ramup, steady state, and " +
                                                            "rampdown seconds");
        System.err.println("\t\tDefaults are 300/300/120");
        System.err.println("\t-W millisecs : Use millisecs pause time between" +
                                                            " requests");
        System.err.println("\t-s : Save all faban output files in temporary " +
                                                            "directory");
        System.err.println("\t-c concurrentRequests: Run c clients " +
                                                            "concurrently");
        System.err.println("\t-t 90% : Target 90th % response rate");
        System.err.println("\t-p file : Use POST data in file");
        System.err.println("\t-b : Send POST data as binary " +
                                                "(application/octet-stream)");
        System.err.println("\t-S : Perform Faban data substitutions on GET " +
                                                "query string or POST data");
        System.err.println("\t-k NOTE : Keep alive is always on");
        System.exit(-1);
    }
}