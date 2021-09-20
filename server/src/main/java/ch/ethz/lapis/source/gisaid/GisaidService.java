package ch.ethz.lapis.source.gisaid;

import ch.ethz.lapis.core.ExhaustibleBlockingQueue;
import ch.ethz.lapis.core.ExhaustibleLinkedBlockingQueue;
import ch.ethz.lapis.util.DeflateSeqCompressor;
import ch.ethz.lapis.util.SeqCompressor;
import ch.ethz.lapis.util.Utils;
import com.mchange.v2.c3p0.ComboPooledDataSource;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.tukaani.xz.XZInputStream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;


public class GisaidService {

    private final ComboPooledDataSource databasePool;
    private final Path workdir;
    private final int maxNumberWorkers;
    private final Path nextalignPath;
    private final GisaidApiConfig gisaidApiConfig;
    private final SeqCompressor seqCompressor = new DeflateSeqCompressor(DeflateSeqCompressor.DICT.REFERENCE);
    private final int batchSize = 100;
    private final Path geoLocationRulesFile;

    public GisaidService(
            ComboPooledDataSource databasePool,
            String workdir,
            int maxNumberWorkers,
            String nextalignPath,
            GisaidApiConfig gisaidApiConfig,
            String geoLocationRulesFile
    ) {
        this.databasePool = databasePool;
        this.workdir = Path.of(workdir);
        this.maxNumberWorkers = maxNumberWorkers;
        this.nextalignPath = Path.of(nextalignPath);
        this.gisaidApiConfig = gisaidApiConfig;
        this.geoLocationRulesFile = Path.of(geoLocationRulesFile);
    }

    public void updateData() throws IOException, SQLException, ParseException, InterruptedException {
        LocalDateTime startTime = LocalDateTime.now();

        // Write the reference sequence and genemap.gff to the workdir
        Path referenceFasta = workdir.resolve("reference.fasta");
        Files.writeString(referenceFasta, ">REFERENCE\n" + Utils.getReferenceSeq() + "\n\n");
        Path geneMapGff = workdir.resolve("genemap.gff");
        Files.writeString(geneMapGff, Utils.getGeneMapGff());

        // Load geo location rules
        GeoLocationMapper geoLocationMapper = new GeoLocationMapper(geoLocationRulesFile);

        // Download data
        Path gisaidDataFile = workdir.resolve("provision.json.xz");
        try {
            downloadDataPackage(
                    new URL(gisaidApiConfig.getUrl()),
                    gisaidApiConfig.getUsername(),
                    gisaidApiConfig.getPassword(),
                    gisaidDataFile
            );
        } catch (IOException e) {
            System.err.println("provision.json.xz could not be downloaded from GISAID");
            throw e;
        }

        // Load the list of all GISAID EPI ISL from the database.
        String loadExistingIdsSql = """
            select gisaid_epi_isl
            from y_gisaid;
        """;
        Set<String> existingGisaidEpiIsls = new HashSet<>();
        try (Connection conn = databasePool.getConnection()) {
            try (Statement statement = conn.createStatement()) {
                try (ResultSet rs = statement.executeQuery(loadExistingIdsSql)) {
                    while (rs.next()) {
                        existingGisaidEpiIsls.add(rs.getString("gisaid_epi_isl"));
                    }
                }
            }
        }

        // Create a queue to store batches and start workers to process them.
        ExhaustibleBlockingQueue<Batch> gisaidBatchQueue = new ExhaustibleLinkedBlockingQueue<>(
                Math.max(4, maxNumberWorkers / 2));
        final ConcurrentLinkedQueue<BatchReport> batchReports = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Exception> unhandledExceptions = new ConcurrentLinkedQueue<>();
        final AtomicBoolean emergencyBrake = new AtomicBoolean(false);
        ExecutorService executor = Executors.newFixedThreadPool(maxNumberWorkers);

        for (int i = 0; i < maxNumberWorkers; i++) {
            //Create a work directory for the worker
            Path workerWorkDir = workdir.resolve("worker-" + i);
            Files.createDirectory(workerWorkDir);

            // Start worker
            final int finalI = i;
            executor.submit(() -> {
                BatchProcessingWorker worker = new BatchProcessingWorker(
                        finalI,
                        workerWorkDir,
                        referenceFasta,
                        databasePool,
                        false,
                        nextalignPath,
                        geneMapGff,
                        seqCompressor
                );
                while (!emergencyBrake.get() && (!gisaidBatchQueue.isExhausted() || !gisaidBatchQueue.isEmpty())) {
                    try {
                        Batch batch = gisaidBatchQueue.poll(5, TimeUnit.SECONDS);
                        if (batch == null) {
                            continue;
                        }
                        BatchReport batchReport = worker.run(batch);
                        batchReports.add(batchReport);
                    } catch (InterruptedException e) {
                        // When the emergency brake is pulled, it is likely that a worker will be interrupted. This is
                        // normal and does not constitute an additional error.
                        if (!emergencyBrake.get()) {
                            unhandledExceptions.add(e);
                        }
                    } catch (Exception e) {
                        unhandledExceptions.add(e);
                        emergencyBrake.set(true);
                        return;
                    }
                }
            });
        }

        // Iterate through the downloaded data package. Group the sequences into batches of $batchSize samples and
        // put the batches into the $gisaidBatchQueue. All found GISAID EPI ISL will be collected in a list.
        List<GisaidEntry> batchEntries = new ArrayList<>();
        BufferedInputStream compressedIn = new BufferedInputStream(new FileInputStream(gisaidDataFile.toFile()));
        XZInputStream decompressedIn = new XZInputStream(compressedIn);
        BufferedReader gisaidReader = new BufferedReader(new InputStreamReader(decompressedIn, StandardCharsets.UTF_8));
        String line;
        int entriesInDataPackage = 0;
        int processedEntries = 0;
        Set<String> gisaidEpiIslInDataPackage = new HashSet<>();
        while ((line = gisaidReader.readLine()) != null) {
            if (emergencyBrake.get()) {
                break;
            }
            entriesInDataPackage++;
            if (entriesInDataPackage % 10000 == 0) {
                System.out.println("[main] Read " + entriesInDataPackage + " in the data package");
            }
            try {
                JSONObject json = (JSONObject) new JSONParser().parse(line);
                String gisaidEpiIsl = (String) json.get("covv_accession_id");
                gisaidEpiIslInDataPackage.add(gisaidEpiIsl);
                GisaidEntry entry = parseDataPackageLine(json, geoLocationMapper);
                batchEntries.add(entry);
                processedEntries++;
            } catch (ParseException e) {
                System.err.println("JSON parsing failed!");
                throw e;
            }
            if (batchEntries.size() >= batchSize) {
                Batch batch = new Batch(batchEntries);
                while (!emergencyBrake.get()) {
                    System.out.println("[main] Try adding a batch");
                    boolean success = gisaidBatchQueue.offer(batch, 5, TimeUnit.SECONDS);
                    if (success) {
                        System.out.println("[main] Batch added");
                        break;
                    }
                }
                batchEntries = new ArrayList<>();
            }
        }
        if (!emergencyBrake.get() && !batchEntries.isEmpty()) {
            Batch lastBatch = new Batch(batchEntries);
            while (!emergencyBrake.get()) {
                System.out.println("[main] Try adding a batch");
                boolean success = gisaidBatchQueue.offer(lastBatch, 5, TimeUnit.SECONDS);
                if (success) {
                    System.out.println("[main] Batch added");
                    break;
                }
            }
            batchEntries = null;
        }
        gisaidBatchQueue.setExhausted(true);

        // If someone pulled the emergency brake, collect some information and send a notification email.
        if (emergencyBrake.get()) {
            System.err.println("Emergency exit!");
            executor.shutdown();
            boolean terminated = executor.awaitTermination(3, TimeUnit.MINUTES);
            if (!terminated) {
                executor.shutdownNow();
            }
        } else {
            // Wait until all batches are finished.
            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        }

        // Perform deletions
        int deleted = 0;
        if (!emergencyBrake.get()) {
            System.out.println("[main] Deleting removed sequences");
            Set<String> toDelete = new HashSet<>(existingGisaidEpiIsls);
            toDelete.removeAll(gisaidEpiIslInDataPackage);
            deleteSequences(toDelete);
            deleted = toDelete.size();
        }

        // Merge the BatchReports to a report and send it by email.
        System.out.println("[main] Preparing final report");
        BatchReport mergedBatchReport = mergeBatchReports(new ArrayList<>(batchReports));
        boolean success = unhandledExceptions.isEmpty()
                && mergedBatchReport.getFailedEntries() < 0.05 * processedEntries;
        FinalReport finalReport = new FinalReport()
                .setSuccess(success)
                .setStartTime(startTime)
                .setEndTime(LocalDateTime.now())
                .setEntriesInDataPackage(entriesInDataPackage)
                .setProcessedEntries(processedEntries)
                .setAddedEntries(mergedBatchReport.getAddedEntries())
                .setUpdatedTotalEntries(mergedBatchReport.getUpdatedTotalEntries())
                .setUpdatedMetadataEntries(mergedBatchReport.getUpdatedMetadataEntries())
                .setUpdatedSequenceEntries(mergedBatchReport.getUpdatedSequenceEntries())
                .setDeletedEntries(deleted)
                .setFailedEntries(mergedBatchReport.getFailedEntries())
                .setUnhandledExceptions(new ArrayList<>(unhandledExceptions));
//        notificationSystem.sendReport(finalReport); TODO

        System.err.println("There are " + unhandledExceptions.size() + " unhandled exceptions.");
        for (Exception unhandledException : unhandledExceptions) {
            unhandledException.printStackTrace();
        }

        // Clean up the work directory
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(workdir)) {
            for (Path path : directory) {
                Files.delete(path);
            }
        }
    }


    private GisaidEntry parseDataPackageLine(
            JSONObject json,
            GeoLocationMapper geoLocationMapper
    ) {
        // Parse date
        String dateOriginal = (String) json.get("covv_collection_date");
        LocalDate date = null;
        try {
            if (dateOriginal != null) {
                date = LocalDate.parse(dateOriginal);
            }
        } catch (DateTimeParseException ignored) {
        }

        // Parse geo data
        String locationString = (String) json.get("covv_location");
        GeoLocation geoLocation;
        if (locationString != null) {
            List<String> locationParts = Arrays.stream(locationString.split("/"))
                    .map(String::trim)
                    .collect(Collectors.toList());
            GeoLocation gisaidDirtyLocation = new GeoLocation();
            if (locationParts.size() > 0) {
                gisaidDirtyLocation.setRegion(locationParts.get(0));
            }
            if (locationParts.size() > 1) {
                gisaidDirtyLocation.setCountry(locationParts.get(1));
            }
            if (locationParts.size() > 2) {
                gisaidDirtyLocation.setDivision(locationParts.get(2));
            }
            if (locationParts.size() > 3) {
                gisaidDirtyLocation.setLocation(locationParts.get(3));
            }
            geoLocation = geoLocationMapper.resolve(gisaidDirtyLocation);
        } else {
            geoLocation = new GeoLocation();
        }

        // Parse age
        String ageString = (String) json.get("covv_patient_age");
        Integer age = Utils.nullableIntegerValue(ageString);

        // Parse sex
        String sexString = (String) json.get("covv_gender");
        if ("male".equalsIgnoreCase(sexString)) {
            sexString = "Male";
        } else if ("female".equalsIgnoreCase(sexString)) {
            sexString = "Female";
        } else {
            sexString = null;
        }

        // Parse date_submitted
        LocalDate dateSubmitted = Utils.nullableLocalDateValue((String) json.get("covv_subm_date"));

        return new GisaidEntry()
                .setGisaidEpiIsl((String) json.get("covv_accession_id"))
                .setStrain((String) json.get("covv_virus_name"))
                .setDate(date)
                .setDateOriginal(dateOriginal)
                .setRegion(geoLocation.getRegion())
                .setCountry(geoLocation.getCountry())
                .setDivision(geoLocation.getDivision())
                .setLocation(geoLocation.getLocation())
                .setHost((String) json.get("covv_host"))
                .setAge(age)
                .setSex(sexString)
                .setPangoLineage((String) json.get("covv_lineage"))
                .setGisaidClade((String) json.get("covv_clade"))
                .setDateSubmitted(dateSubmitted)
                .setSamplingStrategy((String) json.get("covv_sampling_strategy"))
                .setSeqOriginal((String) json.get("sequence"));
    }


    private void deleteSequences(Set<String> gisaidEpiIslToDelete) throws SQLException {
        String sql = """
            delete from y_gisaid where gisaid_epi_isl = ?;
        """;
        try (Connection conn = databasePool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement statement = conn.prepareStatement(sql)) {
                for (String id : gisaidEpiIslToDelete) {
                    statement.setString(1, id);
                    statement.addBatch();
                }
                statement.executeBatch();
                statement.clearBatch();;
            }
            conn.commit();
            conn.setAutoCommit(true);
        }
    }


    private BatchReport mergeBatchReports(List<BatchReport> batchReports) {
        int addedEntries = 0;
        int updatedTotalEntries = 0;
        int updatedMetadataEntries = 0;
        int updatedSequenceEntries = 0;
        int failedEntries = 0;
        for (BatchReport batchReport : batchReports) {
            addedEntries += batchReport.getAddedEntries();
            updatedTotalEntries += batchReport.getUpdatedTotalEntries();
            updatedMetadataEntries += batchReport.getUpdatedMetadataEntries();
            updatedSequenceEntries += batchReport.getUpdatedSequenceEntries();
            failedEntries += batchReport.getFailedEntries();
        }
        return new BatchReport()
                .setAddedEntries(addedEntries)
                .setUpdatedTotalEntries(updatedTotalEntries)
                .setUpdatedMetadataEntries(updatedMetadataEntries)
                .setUpdatedSequenceEntries(updatedSequenceEntries)
                .setFailedEntries(failedEntries);
    }


    private static void downloadDataPackage(
            URL url,
            String username,
            String password,
            Path outputPath
    ) throws IOException {
        String auth = username + ":" + password;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
        String authHeaderValue = "Basic " + new String(encodedAuth);
        HttpURLConnection gisaidApiConnection = (HttpURLConnection) url.openConnection();
        gisaidApiConnection.setRequestProperty("Authorization", authHeaderValue);
        ReadableByteChannel readableByteChannel = Channels.newChannel(gisaidApiConnection.getInputStream());
        FileOutputStream fileOutputStream = new FileOutputStream(outputPath.toFile());
        FileChannel fileChannel = fileOutputStream.getChannel();
        fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
        fileChannel.close();
        fileOutputStream.close();
    }
}