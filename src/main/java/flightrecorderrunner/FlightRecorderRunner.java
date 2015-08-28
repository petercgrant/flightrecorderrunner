package flightrecorderrunner;

import com.oracle.jrockit.jfr.client.EventSettingsBuilder;
import com.oracle.jrockit.jfr.client.FlightRecorderClient;
import com.oracle.jrockit.jfr.client.FlightRecordingClient;
import com.oracle.jrockit.jfr.management.NoSuchRecordingException;
import oracle.jrockit.jfr.RecordingOptions;
import oracle.jrockit.jfr.RecordingOptionsImpl;
import oracle.jrockit.jfr.openmbean.RecordingOptionsType;

import javax.management.*;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenDataException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class FlightRecorderRunner {
    private static final String REGISTER_MBEANS_OPERATION = "registerMBeans";
    private static final String MC_CLASS_NAME = "com.sun.management.MissionControl";
    private static final String MC_MBEAN_NAME = "com.sun.management:type=MissionControl";

    private static final String FR_CLASS_NAME = "oracle.jrockit.jfr.FlightRecorder";
    private static final String FR_MBEAN_NAME = "com.oracle.jrockit:type=FlightRecorder";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printStartUsage();
            printDumpUsage();
            System.exit(64);
        }
        if (isStart(args[0])) {
            // start host:port duration_sec recording_id
            if (args.length < 3 || !isPositiveLong(args[2])) {
                printStartUsage();
                System.exit(64);
            }

            final String host = args[1];
            final Long duration = Long.valueOf(args[2]);
            System.err.println("Attempting to connect to host " + host + " to record for " + duration + " milliseconds");

            //connect to a remote VM using JMX RMI
            JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + host + "/jmxrmi");

            try (JMXConnector jmxConnector = JMXConnectorFactory.connect(url)) {
                final MBeanServerConnection server = jmxConnector.getMBeanServerConnection();
                final FlightRecorderClient flightRecorderClient = initFlightRecorderClient(server);
                final FlightRecordingClient recording = startRecording(flightRecorderClient, "My Recording", duration);
                System.out.println(recording.getId());
            }
        } else if (isDump(args[0])) {
            // dump host:port recording_id filename
            if (args.length < 4 || !isPositiveLong(args[2])) {
                printDumpUsage();
                System.exit(64);
            }

            final String host = args[1];
            final Long recordingId = Long.valueOf(args[2]);
            final String filename = args[3];
            System.err.println("Attempting to connect to host " + host + " to dump recording id " + recordingId + "; results will be stored to local file " + filename);

            //connect to a remote VM using JMX RMI
            JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + host + "/jmxrmi");

            try (JMXConnector jmxConnector = JMXConnectorFactory.connect(url)) {
                final MBeanServerConnection server = jmxConnector.getMBeanServerConnection();
                final FlightRecorderClient flightRecorderClient = initFlightRecorderClient(server);
                final FlightRecordingClient recording = findRecording(flightRecorderClient, recordingId);
                dumpRecording(recording, filename);
                recording.close();
            }
        } else {
            printStartUsage();
            printDumpUsage();
            System.exit(64);
        }
    }

    public static FlightRecordingClient findRecording(FlightRecorderClient flightRecorderClient, Long recordingId) throws NoSuchRecordingException, OpenDataException {
        final List<FlightRecordingClient> recordings = flightRecorderClient.getRecordingObjects();
        for (FlightRecordingClient recording: recordings) {
            if (recording.getId() == recordingId) {
                return recording;
            }
        }
        return null;
    }

    private static void printStartUsage() {
        System.err.println("Usage: java -jar flightrecorderrunner.jar start <host:port> <duration_ms>");
        System.err.println("   Connect to jmx on 'host:port' to start recording for 'duration_ms' milliseconds");
    }

    private static void printDumpUsage() {
        System.err.println("Usage: java -jar flightrecorderrunner.jar dump <host:port> <recording_id> <filename>");
        System.err.println("   Connect to jmx on 'host:port' and dump recording 'recording_id' to local filename 'filename'");
    }

    private static boolean isStart(String arg) {
        return "start".equalsIgnoreCase(arg);
    }

    private static boolean isDump(String arg) {
        return "dump".equalsIgnoreCase(arg);
    }

    public static FlightRecorderClient initFlightRecorderClient(MBeanServerConnection server) throws OperationsException, IOException, MBeanException, ReflectionException {
        final ObjectName mcObjectName = new ObjectName(MC_MBEAN_NAME);
        if (!server.isRegistered(mcObjectName)) {
            server.createMBean(MC_CLASS_NAME, mcObjectName);
            server.invoke(mcObjectName, REGISTER_MBEANS_OPERATION, new Object[0], new String[0]);
        }

        final ObjectName frObjectName = new ObjectName(FR_MBEAN_NAME);
        if (!server.isRegistered(frObjectName)) {
            server.createMBean(FR_CLASS_NAME, frObjectName);
            server.invoke(frObjectName, REGISTER_MBEANS_OPERATION, new Object[0], new String[0]);
        }

        return new FlightRecorderClient(server);
    }

    public static FlightRecordingClient startRecording(FlightRecorderClient flightRecorderClient, String recordingId, Long durationMs) throws NoSuchRecordingException, OpenDataException {
        final FlightRecordingClient recording = flightRecorderClient.createRecordingObject(recordingId);
        final RecordingOptionsType recordingOptionsType = new RecordingOptionsType();
        final RecordingOptions recordingOptionsDefaults = recordingOptionsType.toJavaTypeData(flightRecorderClient.getRecordingOptionsDefaults());
        final RecordingOptionsImpl myRecordingOptions = new RecordingOptionsImpl(recordingOptionsDefaults);
        myRecordingOptions.setDuration(durationMs, TimeUnit.MILLISECONDS);
        //myRecordingOptions.setDestination("/tmp/test.jfr");

        recording.setOptions(recordingOptionsType.toCompositeTypeData(myRecordingOptions));

        final EventSettingsBuilder eventSettingsBuilder = new EventSettingsBuilder();
        for (CompositeData data: flightRecorderClient.getAvailablePresets()) {
            final String name = (String) data.get("name");
            if (name.equals("Profiling")) {
                recording.setEventDefaults(Arrays.asList((CompositeData[]) data.get("settings")));
                System.err.println("Set event defaults to 'Profiling' preset");
            }
        }

        // thread dumps -> (enabled=true, stacktrace=false, threshold=-1, period=10s)
        //eventSettingsBuilder.createSetting("http://www.oracle.com/hotspot/jvm/vm/runtime/thread_dump", true, false, -1, 10000);
        //recording.setEventDefaults(eventSettingsBuilder.createDefaultSettings());
            /*
            for (CompositeData eventSetting: recording.getEventSettings()) {
                final Integer eventid = (Integer) eventSetting.get("id");
                System.out.println(eventSetting);
            }
            */
        recording.start();
        System.err.println("Started recording...");
        return recording;
    }

    public static long dumpRecording(FlightRecordingClient recording, String outputFilename) throws InterruptedException, IOException {
        while (recording.isRunning()) {
            Thread.sleep(1000);
        }
        System.err.println("Finished recording. Saving to " + outputFilename);
        try (InputStream in = recording.openStreamObject(); OutputStream out = new FileOutputStream(outputFilename)) {
            return copy(in, out);
        }
    }

    private static boolean isPositiveLong(String arg) {
        try {
            final Long value = Long.valueOf(arg);
            return value > 0L;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    public static long copy(InputStream from, OutputStream to)
            throws IOException {
        final byte[] buf = new byte[4096];
        long total = 0;
        while (true) {
            int r = from.read(buf);
            if (r == -1) {
                break;
            }
            to.write(buf, 0, r);
            total += r;
        }
        return total;
    }
}
