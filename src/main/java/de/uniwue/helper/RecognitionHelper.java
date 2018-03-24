package de.uniwue.helper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import org.apache.commons.io.FilenameUtils;

import de.uniwue.config.ProjectConfiguration;
import de.uniwue.feature.ProcessHandler;

/**
 * Helper class for recognition module
 */
public class RecognitionHelper {
    /**
     * Object to access project configuration
     */
    private ProjectConfiguration projConf;

    /**
     * Image type of the project
     * Possible values: { Binary, Gray }
     */
    private String projectImageType;

    /**
     * Helper object for process handling
     */
    private ProcessHandler processHandler;

    /**
     * Progress of the Recognition process
     */
    private int progress = -1;

    /**
     * Indicates if a Recognition process is already running
     */
    private boolean RecognitionRunning = false;

    /**
     * Structure to monitor the progress of the process
     * pageId : segmentId : lineSegmentId : processedState
     *
     * Structure example:
     * {
     *     "0002": {
     *         "0002__000__paragraph" : {
     *             "0002__000__paragraph__000" : true,
     *             "0002__000__paragraph__001" : false,
     *             ...
     *         },
     *         ...
     *     },
     *     ...
     * }
     */
    private TreeMap<String,TreeMap<String, TreeMap<String, Boolean>>> processState =
        new TreeMap<String, TreeMap<String, TreeMap<String, Boolean>>>();

    /**
     * Constructor
     *
     * @param projectDir Path to the project directory
     * @param projectImageType Type of the project (binary, gray)
     */
    public RecognitionHelper(String projectDir, String projectImageType) {
        this.projectImageType = projectImageType;
        projConf = new ProjectConfiguration(projectDir);
        processHandler = new ProcessHandler();
    }

    /**
     * Gets the process handler object
     *
     * @return Returns the process Helper
     */
    public ProcessHandler getProcessHandler() {
        return processHandler;
    }

    /**
     * Initializes the structure with which the progress of the process can be monitored
     *
     * @param pageIds Identifiers of the chosen pages (e.g 0002,0003)
     * @throws IOException
     */
    public void initialize(List<String> pageIds) throws IOException {
        // Initialize the status structure
        processState = new TreeMap<String, TreeMap<String, TreeMap<String, Boolean>>>();

        for (String pageId : pageIds) {
            TreeMap<String, TreeMap<String, Boolean>> segments = new TreeMap<String, TreeMap<String, Boolean>>();
            // File depth of 1 -> no recursive (file)listing
            File[] lineSegmentDirectories = new File(projConf.PAGE_DIR + pageId).listFiles(File::isDirectory);
            if (lineSegmentDirectories.length != 0) {
                for (File dir : lineSegmentDirectories) {
                    TreeMap<String, Boolean> lineSegments = new TreeMap<String, Boolean>();
                    Files.walk(Paths.get(dir.getAbsolutePath()), 1)
                    .map(Path::toFile)
                    .filter(fileEntry -> fileEntry.isFile())
                    .filter(fileEntry -> fileEntry.getName().endsWith(projConf.getImageExtensionByType(projectImageType)))
                    .forEach(
                        fileEntry -> {
                            // Line segments have one of the following endings: ".bin.png" | ".nrm.png"
                            // Therefore both extensions need to be removed
                            String lineSegmentId = FilenameUtils.removeExtension(FilenameUtils.removeExtension(fileEntry.getName()));
                            lineSegments.put(lineSegmentId, false);
                        }
                    );
                    segments.put(dir.getName(), lineSegments);
                }
            }

            processState.put(pageId, segments);
        }
    }

    /**
     * Returns the absolute path of all line segment images for the pages in the processState
     *
     * @param pageIds Identifiers of the chosen pages (e.g 0002,0003)
     * @return List of line segment images
     * @throws IOException 
     */
    public List<String> getLineSegmentImagesForCurrentProcess(List<String> pageIds) throws IOException {
        List<String> LineSegmentsOfPage = new ArrayList<String>();
        for (String pageId : processState.keySet()) {
            for (String segmentId : processState.get(pageId).keySet()) {
                for (String lineSegmentId : processState.get(pageId).get(segmentId).keySet()) {
                    LineSegmentsOfPage.add(projConf.PAGE_DIR + pageId + File.separator + segmentId +
                        File.separator + lineSegmentId + projConf.getImageExtensionByType(projectImageType));
                }
            }
        }
        return LineSegmentsOfPage;
    }

    /**
     * Returns the progress of the process
     *
     * @return Progress percentage
     * @throws IOException 
     */
    public int getProgress() throws IOException {
        // Prevent function from calculation progress if process is not running
        if (RecognitionRunning == false)
            return progress;

        int lineSegmentCount = 0;
        int processedLineSegmentCount = 0;
         // Identify how many line segments are already processed
        for (String pageId : processState.keySet()) {
            for (String segmentId : processState.get(pageId).keySet()) {
                for (String lineSegmentId : processState.get(pageId).get(segmentId).keySet()) {
                    lineSegmentCount += 1;

                    if(processState.get(pageId).get(segmentId).get(lineSegmentId)) {
                        processedLineSegmentCount += 1;
                        continue;
                    }

                    if (new File(projConf.PAGE_DIR + pageId + File.separator + segmentId +
                            File.separator + lineSegmentId + projConf.REC_EXT).exists()) {
                        processState.get(pageId).get(segmentId).put(lineSegmentId, true);
                    }
                }
            }
        }
        return (progress != 100) ? (int) ((double)processedLineSegmentCount / lineSegmentCount * 100) : 100;
    }

    /**
     * Executes OCR on a list of pages
     * Achieved with the help of the external python program "ocropus-rpred"
     *
     * @param pageIds Identifiers of the pages (e.g 0002,0003)
     * @param cmdArgs Command line arguments for "ocropus-rpred"
     * @throws IOException
     */
    public void RecognizeImages(List<String> pageIds, List<String> cmdArgs) throws IOException {
        RecognitionRunning = true;
        progress = 0;

        // Reset recognition data
        deleteOldFiles(pageIds);
        initialize(pageIds);

        List<String> command = new ArrayList<String>();
        List<String> lineSegmentImages = getLineSegmentImagesForCurrentProcess(pageIds);
        for (String lineSegmentImage : lineSegmentImages) {
            // Add affected line segment images with their absolute path to the command list
            command.add(lineSegmentImage);
        }
        command.addAll(cmdArgs);

        processHandler = new ProcessHandler();
        processHandler.setFetchProcessConsole(true);
        processHandler.startProcess("ocropus-rpred", command, false);

        progress = 100;
        RecognitionRunning = false;
    }

    /**
     * Resets the progress (use if an error occurs)
     */
    public void resetProgress() {
        RecognitionRunning = false;
        progress = -1;
    }

    /**
     * Cancels the process
     */
    public void cancelProcess() {
        if (processHandler != null)
            processHandler.stopProcess();
        RecognitionRunning = false;
    }

    /**
     * Returns the Ids of the pages, for which region extraction was already executed
     * TODO: Check if line segmentation process was executed, not region extraction!
     *
     * @return List with page ids
     */
    public ArrayList<String> getValidPageIdsforRecognition() {
        ArrayList<String> validPageIds = new ArrayList<String>();
        File pageDir = new File(projConf.PAGE_DIR);
        if (!pageDir.exists())
            return validPageIds;

        File[] directories = pageDir.listFiles(File::isDirectory);
        for(File file: directories) { 
            validPageIds.add(file.getName());
        }
        Collections.sort(validPageIds);

        return validPageIds;
    }

    /**
     * Returns the Recognition status
     *
     * @return status if the process is running
     */
    public boolean isRecongitionRunning() {
        return RecognitionRunning;
    }

    /**
     * Deletion of old process related files
     *
     * @param pageIds Identifiers of the pages (e.g 0002,0003)
     */
    public void deleteOldFiles(List<String> pageIds) {
        for(String pageId : pageIds) {
            File pageDirectory = new File(projConf.PAGE_DIR + pageId);
            if (!pageDirectory.exists())
                return;

            File[] lineSegmentDirectories = pageDirectory.listFiles(File::isDirectory);
            if (lineSegmentDirectories.length != 0) {
                for (File dir : lineSegmentDirectories) {
                    File[] txtFiles = new File(dir.getAbsolutePath()).listFiles((d, name) -> name.endsWith(projConf.REC_EXT));
                    // Delete .txt files that store the recognized text
                    for (File txtFile : txtFiles) {
                        txtFile.delete();
                    }
                }
            }
        }
    }

    /**
     * Checks if process depending files already exist
     *
     * @param pageIds Identifiers of the pages (e.g 0002,0003)
     * @return Information if files exist
     */
    public boolean doOldFilesExist(String[] pageIds) {
        for(String pageId : pageIds) {
            File pageDirectory = new File(projConf.PAGE_DIR + pageId);
            if (!pageDirectory.exists())
                break;

            File[] lineSegmentDirectories = pageDirectory.listFiles(File::isDirectory);
            for (File dir : lineSegmentDirectories) {
                if (new File(dir.getAbsolutePath()).listFiles((d, name) -> name.endsWith(projConf.REC_EXT)).length != 0)
                    return true;
            }
        }
        return false;
    }
}
