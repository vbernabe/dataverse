/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.harvard.iq.dataverse.datasetutility;

import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.DataFileServiceBean;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetServiceBean;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.EjbDataverseEngine;
import edu.harvard.iq.dataverse.FileMetadata;
import edu.harvard.iq.dataverse.PermissionServiceBean;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.users.User;
import edu.harvard.iq.dataverse.engine.command.Command;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.impl.UpdateDatasetCommand;
import edu.harvard.iq.dataverse.ingest.IngestServiceBean;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.EJBException;
import javax.ejb.Stateless;
import javax.enterprise.context.SessionScoped;
import javax.inject.Named;
import javax.validation.ConstraintViolation;

/**
 *  Methods to add or replace a single file.
 * 
 * @author rmp553
 */
public class AddReplaceFileHelper{
    
    private static final Logger logger = Logger.getLogger(AddReplaceFileHelper.class.getCanonicalName());

    
    public static String FILE_ADD_OPERATION = "FILE_ADD_OPERATION";
    public static String FILE_REPLACE_OPERATION = "FILE_REPLACE_OPERATION";
    
            
    private String currentOperation;
    
    // -----------------------------------
    // All the needed EJBs, passed to the constructor
    // -----------------------------------
    private IngestServiceBean ingestService;
    private DatasetServiceBean datasetService;
    private DataFileServiceBean fileService;        
    private PermissionServiceBean permissionService;
    private EjbDataverseEngine commandEngine;
    
    // -----------------------------------
    // Instance variables directly added
    // -----------------------------------
    private Dataset dataset;                    // constructor
    private DataverseRequest dvRequest;         // constructor
    private InputStream newFileInputStream;     // step 20
    private String newFileName;                 // step 20
    private String newFileContentType;          // step 20
    // -- Optional  
    private DataFile fileToReplace;             // step 25
    
    
    // Instance variables derived from other input
    private User user;
    private DatasetVersion workingVersion;
    List<DataFile> newFileList;
    List<DataFile> filesToAdd;
   
    
    // For error handling
    private boolean errorFound;
    private List<String> errorMessages;
    
    
  //  public AddReplaceFileHelper(){
  //      throw new IllegalStateException("Must be called with a dataset and or user");
  //  }
    
    
    /** 
     * MAIN CONSTRUCTOR -- minimal requirements
     * 
     * @param dataset
     * @param dvRequest 
     */

    public AddReplaceFileHelper(DataverseRequest dvRequest, 
                            IngestServiceBean ingestService,                            
                            DatasetServiceBean datasetService,
                            DataFileServiceBean fileService,
                            PermissionServiceBean permissionService,
                            EjbDataverseEngine commandEngine){

        // ---------------------------------
        // make sure DataverseRequest isn't null and has a user
        // ---------------------------------
        if (dvRequest == null){
            throw new NullPointerException("dvRequest cannot be null");
        }
        if (dvRequest.getUser() == null){
            throw new NullPointerException("dvRequest cannot have a null user");
        }

        // ---------------------------------
        // make sure services aren't null
        // ---------------------------------
        if (ingestService == null){
            throw new NullPointerException("ingestService cannot be null");
        }
        if (datasetService == null){
            throw new NullPointerException("datasetService cannot be null");
        }
        if (fileService == null){
            throw new NullPointerException("fileService cannot be null");
        }
        if (permissionService == null){
            throw new NullPointerException("ingestService cannot be null");
        }
        if (commandEngine == null){
            throw new NullPointerException("commandEngine cannot be null");
        }

        // ---------------------------------
        
        this.ingestService = ingestService;
        this.datasetService = datasetService;
        this.fileService = fileService;
        this.permissionService = permissionService;
        this.commandEngine = commandEngine;
        
        
        
        initErrorHandling();
        
        // Initiate instance vars
        this.dataset = null;
        this.dvRequest = dvRequest;
        this.user = dvRequest.getUser();
        
    }
    
    /**
     * After the constructor, this method is called to add a file
     * 
     * @param dataset
     * @param newFileName
     * @param newFileContentType
     * @param newFileInputStream
     * @return 
     */
    public boolean runAddFile(Dataset dataset, String newFileName, String newFileContentType, InputStream newFileInputStream){
        
        this.currentOperation = FILE_ADD_OPERATION;
        
        return this.runAddReplaceFile(dataset, newFileName, newFileContentType, newFileInputStream, null);
    }
    

    /**
     * After the constructor, this method is called to replace a file
     * 
     * @param dataset
     * @param newFileName
     * @param newFileContentType
     * @param newFileInputStream
     * @return 
     */
    public boolean runReplaceFile(Dataset dataset, String newFileName, String newFileContentType, InputStream newFileInputStream, Long oldFileId){
        
        if (oldFileId==null){
            throw new NullPointerException("For a replace operation, oldFileId cannot be null");
        }
        
        this.currentOperation = FILE_REPLACE_OPERATION;
        
        return this.runAddReplaceFile(dataset, newFileName, newFileContentType, newFileInputStream, oldFileId);
    }
    
    /**
     * Here we're going to run through the steps to ADD or REPLACE a file
     * 
     * The difference between ADD and REPLACE (add/delete) is:
     * 
     *  oldFileId - For ADD, set to null
     *  oldFileId - For REPLACE, set to id of file to replace 
     * 
     * 
     * @return 
     */
    private boolean runAddReplaceFile(Dataset dataset,  
            String newFileName, String newFileContentType, InputStream newFileInputStream,
            Long oldFileId){
        
        initErrorHandling();

        
        if (this.hasError()){
            return false;
        }

        msgt("step_001_loadDataset");
        if (!this.step_001_loadDataset(dataset)){
            return false;
        }
        
        msgt("step_010_VerifyUserAndPermissions");
        if (!this.step_010_VerifyUserAndPermissions()){
            return false;
            
        }

        msgt("step_020_loadNewFile");
        if (!this.step_020_loadNewFile(newFileName, newFileContentType, newFileInputStream)){
            return false;
            
        }

        // Replace only step!
        if (isFileReplaceOperation()){
            
            msgt("step_025_loadFileToReplaceById");
            if (!this.step_025_loadFileToReplaceById(oldFileId)){
                return false;
            }
        }
        
        msgt("step_030_createNewFilesViaIngest");
        if (!this.step_030_createNewFilesViaIngest()){
            return false;
            
        }

        msgt("step_050_checkForConstraintViolations");
        if (!this.step_050_checkForConstraintViolations()){
            return false;
            
        }

        msgt("step_060_addFilesViaIngestService");
        if (!this.step_060_addFilesViaIngestService()){
            return false;
            
        }

        if (this.isFileReplaceOperation()){
            msgt("step_080_run_update_dataset_command_for_replace");
            if (!this.step_080_run_update_dataset_command_for_replace()){
                return false;            
            }
            
        }else{
            msgt("step_070_run_update_dataset_command");
            if (!this.step_070_run_update_dataset_command()){
                return false;            
            }
        }
        
        msgt("step_090_notifyUser");
        if (!this.step_090_notifyUser()){
            return false;            
        }

        msgt("step_100_startIngestJobs");
        if (!this.step_100_startIngestJobs()){
            return false;            
        }

        
        return true;
    }

    /**
     *  Get for currentOperation
     *  @return String
     */
    public String getCurrentOperation(){
        return this.currentOperation;
    }

    public boolean isFileReplaceOperation(){
    
        return this.currentOperation == FILE_REPLACE_OPERATION;
    }

    public boolean isFileAddOperation(){
    
        return this.currentOperation == FILE_ADD_OPERATION;
    }

    /**
     * Initialize error handling vars
     */
    private void initErrorHandling(){

        this.errorFound = false;
        this.errorMessages = new ArrayList<>();
        
    }
         
 
    
    

    /**
     * Add error message
     * 
     * @param errMsg 
     */
    private void addError(String errMsg){
        
        if (errMsg == null){
            throw new NullPointerException("errMsg cannot be null");
        }
        this.errorFound = true;
 
        logger.fine(errMsg);
        this.errorMessages.add(errMsg);
    }
    

    private void addErrorSevere(String errMsg){
        
        if (errMsg == null){
            throw new NullPointerException("errMsg cannot be null");
        }
        this.errorFound = true;
 
        logger.severe(errMsg);
        this.errorMessages.add(errMsg);
    }

    
    /**
     * Was an error found?
     * 
     * @return 
     */
    public boolean hasError(){
        return this.errorFound;
        
    }
    
    /**
     * get error messages
     * 
     * @return 
     */
    public List<String> getErrorMessages(){
        return this.errorMessages;
    }   

    /**
     * get error messages as string 
     * 
     * @param joinString
     * @return 
     */
    public String getErrorMessagesAsString(String joinString){
        if (joinString==null){
            joinString = "\n";
        }
        return String.join(joinString, this.errorMessages);
    }   

    
     /**
     * 
     */
    private boolean step_001_loadDataset(Dataset selectedDataset){

        if (this.hasError()){
            return false;
        }

        if (selectedDataset == null){
            this.addErrorSevere("The dataset cannot be null");
            return false;
        }

        dataset = selectedDataset;
        
        return true;
    }

    
    /**
     * 
     */
    private boolean step_001_loadDatasetById(Long datasetId){
        
        if (this.hasError()){
            return false;
        }

        if (datasetId == null){
            this.addErrorSevere("The datasetId cannot be null");
            return false;
        }
        
        Dataset yeDataset = datasetService.find(datasetId);
        if (yeDataset == null){
            this.addError("There was no dataset found for id: " + datasetId);
            return false;
        }      
       
        return step_001_loadDataset(yeDataset);
    }
    
    
    
        
    
    /**
     *  Step 10 Verify User and Permissions
     * 
     * 
     * @return 
     */
    private boolean step_010_VerifyUserAndPermissions(){
        
        if (this.hasError()){
            return false;
        }
        
        msg("dataset:" + dataset.toString());
        msg("Permission.EditDataset:" + Permission.EditDataset.toString());
        msg("dvRequest:" + dvRequest.toString());
        msg("permissionService:" + permissionService.toString());
        
        if (!permissionService.request(dvRequest).on(dataset).has(Permission.EditDataset)){
           String errMsg = "You do not have permission to this dataset.";
           addError(errMsg);
           return false;
        }
        return true;

    }
    
    
    private boolean step_020_loadNewFile(String fileName, String fileContentType, InputStream fileInputStream){
        
        if (this.hasError()){
            return false;
        }
        
        if (fileName == null){
            String errMsg = "The fileName cannot be null.";
            this.addErrorSevere(errMsg);
            return false;
            
        }

        if (fileContentType == null){
            String errMsg = "The fileContentType cannot be null.";
            this.addErrorSevere(errMsg);
            return false;
            
        }

        if (fileName == null){
            String errMsg = "The fileName cannot be null.";
            this.addErrorSevere(errMsg);
            return false;
            
        }
        

        if (fileInputStream == null){
            String errMsg = "The fileInputStream cannot be null.";
            this.addErrorSevere(errMsg);
            return false;
        }
       
        newFileName = fileName;
        newFileContentType = fileContentType;
        newFileInputStream = fileInputStream;
        
        return true;
    }
    
    /**
     * Optional: old file to replace
     * 
     * @param oldFile
     * @return 
     */
    private boolean step_025_loadFileToReplace(DataFile existingFile){

        if (this.hasError()){
            return false;
        }
        
        if (existingFile == null){
            this.addErrorSevere("The existingFile to replace cannot be null");
            return false;
        }
        
        if (existingFile.getOwner() != this.dataset){
            String errMsg = "This file does not belong to the datset";
            addError(errMsg);
            return false;
        }
        
        fileToReplace = existingFile;
        
        return true;
    }

    
    /**
     * Optional: old file to replace
     * 
     * @param oldFile
     * @return 
     */
    private boolean step_025_loadFileToReplaceById(Long dataFileId){
        
        if (this.hasError()){
            return false;
        }
        
        // This shouldn't happen, the public replace method should through
        //  a NullPointerException
        //
        if (dataFileId == null){
            this.addError("The dataFileId cannot be null");
            return false;
        }
        
        DataFile existingFile = fileService.find(dataFileId);
        if (existingFile == null){
            this.addError("Replacement file not found.  There was no file found for id: " + dataFileId);
            return false;
        }      
        
        return step_025_loadFileToReplace(existingFile);
    }
    
    
    private boolean step_030_createNewFilesViaIngest(){
        
        if (this.hasError()){
            return false;
        }

        // Load the working version of the Dataset
        workingVersion = dataset.getEditVersion();
        
        try {
            newFileList = ingestService.createDataFiles(workingVersion,
                    this.newFileInputStream,
                    this.newFileName,
                    this.newFileContentType);
        } catch (IOException ex) {
            String errMsg = "There was an error when trying to add the new file.";
            this.addErrorSevere(errMsg);
            logger.severe(ex.toString());
            return false;
        }
        
        
        /**
         * This only happens:
         *  (1) the dataset was empty
         *  (2) the new file (or new file unzipped) did not ingest via "createDataFiles"
         */
        if (newFileList.isEmpty()){
            this.addErrorSevere("Sorry! An error occurred and the new file was not added.");
            return false;
        }
        
        if (!this.run_auto_step_040_checkForDuplicates()){
            return false;
        }
                       
        return this.run_auto_step_045_checkForFileReplaceDuplicate();
    }
    
    /**
     * This is always run after step 30
     * 
     * @return 
     */
    private boolean run_auto_step_040_checkForDuplicates(){
        
        msgt("run_auto_step_040_checkForDuplicates");
        if (this.hasError()){
            return false;
        }
        
        // Double checked -- this check also happens in step 30
        //
        if (newFileList.isEmpty()){
            this.addErrorSevere("Sorry! An error occurred and the new file was not added.");
            return false;
        }

        // Initialize new file list
        this.filesToAdd = new ArrayList();

        String warningMessage  = null;
        

        // -----------------------------------------------------------
        // Iterate through the recently ingest files
        // -----------------------------------------------------------
        for (DataFile df : newFileList){
             msg("Checking file: " + df.getFileMetadata().getLabel());

            // -----------------------------------------------------------
            // (1) Check for ingest warnings
            // -----------------------------------------------------------
            if (df.isIngestProblem()) {
                if (df.getIngestReportMessage() != null) {
                    // may collect multiple error messages
                    this.addError(df.getIngestReportMessage());
                }
                df.setIngestDone();
            }
          
            
            // -----------------------------------------------------------
            // (2) Check for duplicates
            // -----------------------------------------------------------                        
            if (DuplicateFileChecker.isDuplicateOriginalWay(workingVersion, df.getFileMetadata())){

                String dupeName = df.getFileMetadata().getLabel();
                removeLinkedFileFromDataset(dataset, df);
                //abandonOperationRemoveAllNewFilesFromDataset();
                this.addErrorSevere("This file has a duplicate already in the dataset: " + dupeName);                
            }else{
                filesToAdd.add(df);
            }
        }
        
        if (this.hasError()){
            filesToAdd.clear();
            return false;
        }
        
        return true;
    } // end run_auto_step_040_checkForDuplicates
    
    
    /**
     * This is always checked.   
     * 
     * For ADD: If there is not replacement file, then the check is considered a success
     * For REPLACE: The checksum is examined against the "filesToAdd" list
     * 
     */
    private boolean run_auto_step_045_checkForFileReplaceDuplicate(){
        
        if (this.hasError()){
            return false;
        }

        // Not a FILE REPLACE operation -- skip this step!!
        //
        if (!isFileReplaceOperation()){
            return true;
        }

        
        if (filesToAdd.isEmpty()){
            // This error shouldn't happen if steps called in sequence....
            this.addErrorSevere("There are no files to add.  (This error shouldn't happen if steps called in sequence....checkForFileReplaceDuplicate)");                
            return false;
        }
        
        
        if (this.fileToReplace == null){
            // This error shouldn't happen if steps called correctly
            this.addErrorSevere("The fileToReplace cannot be null. (This error shouldn't happen if steps called in sequence....checkForFileReplaceDuplicate)");                
            return false;
        }
    
        for (DataFile df : filesToAdd){
            
            if (df.getCheckSum() == fileToReplace.getCheckSum()){
                this.addError("The new file,\"" + df.getFileMetadata().getLabel() 
                        + "\" has the same content as the replacment file, \"" 
                        + fileToReplace.getFileMetadata().getLabel() + "\" .");                
                
                removeLinkedFileFromDataset(dataset, df);   // Is this correct, if multiple files added in case of .shp or .zip, shouldn't they all be removed?             
                //this.abandonOperationRemoveAllNewFilesFromDataset(); // Is this correct, if multiple files, shouldn't they all be removed?
                return false;
            }
        }
        
        return true;
        
    } // end run_auto_step_045_checkForFileReplaceDuplicate
    
    
    private boolean abandonOperationRemoveAllNewFilesFromDataset(){
        
        if (filesToAdd.isEmpty()){
            return true;
        }
        
        for (DataFile df : filesToAdd){
            this.removeLinkedFileFromDataset(dataset, df); // Is this correct, if multiple files, shouldn't they all be removed?
        }
        return true;
    }
    
    
    private boolean step_050_checkForConstraintViolations(){
                
        if (this.hasError()){
            return false;
        }
        
        if (filesToAdd.isEmpty()){
            // This error shouldn't happen if steps called in sequence....
            this.addErrorSevere("There are no files to add.  (This error shouldn't happen if steps called in sequence....)");                
            return false;
        }

        // -----------------------------------------------------------
        // Iterate through checking for constraint violations
        //  Gather all error messages
        // -----------------------------------------------------------   
        Set<ConstraintViolation> constraintViolations = workingVersion.validate();    

        // -----------------------------------------------------------   
        // No violations found
        // -----------------------------------------------------------   
        if (constraintViolations.isEmpty()){
            return true;
        }
        
        // -----------------------------------------------------------   
        // violations found: gather all error messages
        // -----------------------------------------------------------   
        List<String> errMsgs = new ArrayList<>();
        for (ConstraintViolation violation : constraintViolations){
            this.addError(violation.getMessage());
        }
        
        return this.hasError();
    }
    
    
    private boolean step_060_addFilesViaIngestService(){
                       
        if (this.hasError()){
            return false;
        }
                
        if (filesToAdd.isEmpty()){
            // This error shouldn't happen if steps called in sequence....
            this.addErrorSevere("There are no files to add.  (This error shouldn't happen if steps called in sequence....)");                
            return false;
        }
        
        ingestService.addFiles(workingVersion, filesToAdd);

        return true;
    }
    
    
    /**
     * Create and run the update dataset command
     * 
     * @return 
     */
    private boolean step_070_run_update_dataset_command(){
        
        if (this.hasError()){
            return false;
        }

        Command<Dataset> update_cmd;
        update_cmd = new UpdateDatasetCommand(dataset, dvRequest);
        ((UpdateDatasetCommand) update_cmd).setValidateLenient(true);  
        
        try {            
            commandEngine.submit(update_cmd);
        } catch (CommandException ex) {
            this.addErrorSevere("Failed to update the dataset.  Please contact the administrator");
            logger.severe(ex.getMessage());
            return false;
        }catch (EJBException ex) {
            this.addErrorSevere("Failed to update the dataset.  Please contact the administrator");
            logger.severe(ex.getMessage());
            return false;
        } 
        return true;
    }

    
    private boolean step_auto_085_delete_file_to_replace_from_working_version(){

        msgt("step_auto_085_delete_file_to_replace_from_working_version 1");

        if (!isFileReplaceOperation()){
            // Shouldn't happen!
            this.addErrorSevere("This should ONLY be called for file replace operations!! (step_auto_085_delete_file_to_replace_from_working_version");
            return false;
        }
        msg("step_auto_085_delete_file_to_replace_from_working_version 1");

        if (this.hasError()){
            return false;
        }

        msg("step_auto_085_delete_file_to_replace_from_working_version 2");
        
        // 2. delete the filemetadata from the version: 
        //fmit = dataset.getEditVersion().getFileMetadatas().iterator();
        Iterator fmit = workingVersion.getFileMetadatas().iterator();
        msg("step_auto_085_delete_file_to_replace_from_working_version 3");
        msg("-------------------------");
        msg("File to replace getId: " + fileToReplace.getId());
        msg("File to replace getCheckSum: " + fileToReplace.getCheckSum());
        msg("File to replace getFileMetadata: " + fileToReplace.getFileMetadata());
        msg("File to replace getLabel: " + fileToReplace.getFileMetadata().getLabel());
        msg("-------------------------");
        
        
        while (fmit.hasNext()) {
            msg("-------------------------");
            msg("step_auto_085_delete_file_to_replace_from_working_version 4");
            FileMetadata fmd = (FileMetadata) fmit.next();
            msg("   ....getLabel: " + fmd.getLabel());
            msg("   ....getId: " + fmd.getId());
            msg("   ....getDataFile: " + fmd.getDataFile().toString());
            msg("   ....getDataFile id: " + fmd.getDataFile().getId());
            if (fmd.getId() != null){
                msg("step_auto_085_delete_file_to_replace_from_working_version 5");
                msg("fileToReplace.getStorageIdentifier: " + fileToReplace.getStorageIdentifier());
                msg("fmd.getDataFile().getStorageIdentifier(): " + fmd.getDataFile().getStorageIdentifier());
                if (fileToReplace.getStorageIdentifier().equals(fmd.getDataFile().getStorageIdentifier())) {
                    msg("step_auto_085_delete_file_to_replace_from_working_version 6");
                    fmit.remove();
                    return true;
                }
            }
        }
        return true;
        //this.addErrorSevere("Could not find file to replace in the working DatasetVersion");
        //return false;
    }
    
    private boolean step_080_run_update_dataset_command_for_replace(){

        if (!isFileReplaceOperation()){
            // Shouldn't happen!
            this.addErrorSevere("This should ONLY be called for file replace operations!! (step_080_run_update_dataset_command_for_replace");
            return false;
        }

        if (this.hasError()){
            return false;
        }
        msg("step_080_run_update_dataset_command_for_replace 1");
        // -----------------------------------------------------------
        // Remove the "fileToReplace" from the current working version
        // -----------------------------------------------------------
        if (!step_auto_085_delete_file_to_replace_from_working_version()){
            return false;
        }

        msg("step_080_run_update_dataset_command_for_replace 2");

        // -----------------------------------------------------------
        // Make list of files to delete -- e.g. the single "fileToReplace"
        // -----------------------------------------------------------
        List<FileMetadata> filesToBeDeleted = new ArrayList();
        filesToBeDeleted.add(fileToReplace.getFileMetadata());

        msg("step_080_run_update_dataset_command_for_replace 3");

        
        // -----------------------------------------------------------
        // Set the "root file ids" and "previous file ids"
        // -----------------------------------------------------------
        for (DataFile df : filesToAdd){           
            df.setPreviousDataFileID(fileToReplace.getId());
            df.setRootDataFileId(fileToReplace.getRootDataFileId());
        }
        
        msg("step_080_run_update_dataset_command_for_replace 4");

        
        Command<Dataset> update_cmd;
        update_cmd = new UpdateDatasetCommand(dataset, dvRequest, filesToBeDeleted);

        msg("step_080_run_update_dataset_command_for_replace 5");

        ((UpdateDatasetCommand) update_cmd).setValidateLenient(true);
        
        msg("step_080_run_update_dataset_command_for_replace 6");

        try {            
              commandEngine.submit(update_cmd);
          } catch (CommandException ex) {
              this.addErrorSevere("Failed to update the dataset.  Please contact the administrator");
              logger.severe(ex.getMessage());
              return false;
          }catch (EJBException ex) {
              this.addErrorSevere("Failed to update the dataset.  Please contact the administrator");
              logger.severe(ex.getMessage());
              return false;
          } 
          return true;
    }
    
    
    private boolean step_090_notifyUser(){
        if (this.hasError()){
            return false;
        }
       
        // Create a notification!
       
        // skip for now 
        return true;
    }
    

    private boolean step_100_startIngestJobs(){
        if (this.hasError()){
            return false;
        }
        
        // clear old file list
        //
        filesToAdd.clear();

        
        // start the ingest!
        //
        ingestService.startIngestJobs(dataset, dvRequest.getAuthenticatedUser());
        
        return true;
    }

    
    private void msg(String m){
        System.out.println(m);
    }
    private void dashes(){
        msg("----------------");
    }
    private void msgt(String m){
        dashes(); msg(m); dashes();
    }
    
    
  
    /**
     * When a duplicate file is found after the initial ingest,
     * remove the file from the dataset because
     * createDataFiles has already linked it to the dataset:
     *  - first, through the filemetadata list
     *  - then through tht datafiles list
     * 
     * 
     * @param dataset
     * @param dataFileToRemove 
     */
    private boolean removeLinkedFileFromDataset(Dataset dataset, DataFile dataFileToRemove){
        
        if (dataset==null){
            this.addErrorSevere("dataset cannot be null in removeLinkedFileFromDataset");
            return false;
        }
        
        if (dataFileToRemove==null){
            this.addErrorSevere("dataFileToRemove cannot be null in removeLinkedFileFromDataset");
            return false;
        }
        
        // -----------------------------------------------------------
        // (1) Remove file from filemetadata list
        // -----------------------------------------------------------                        
        Iterator<FileMetadata> fmIt = dataset.getEditVersion().getFileMetadatas().iterator();
        msgt("Clear FileMetadatas");
        while (fmIt.hasNext()) {
            FileMetadata fm = fmIt.next();
            msg("Check: " + fm);
            if (fm.getId() == null && dataFileToRemove.getStorageIdentifier().equals(fm.getDataFile().getStorageIdentifier())) {
                msg("Got It! ");
                fmIt.remove();
                break;
            }
        }
        
        
        // -----------------------------------------------------------
        // (2) Remove file from datafiles list
        // -----------------------------------------------------------                        
        Iterator<DataFile> dfIt = dataset.getFiles().iterator();
        msgt("Clear Files");
        while (dfIt.hasNext()) {
            DataFile dfn = dfIt.next();
            msg("Check: " + dfn);
            if (dfn.getId() == null && dataFileToRemove.getStorageIdentifier().equals(dfn.getStorageIdentifier())) {
                msg("Got It! try to remove from iterator");
                
                dfIt.remove();
                msg("it work");
                
                break;
            }else{
                msg("...ok");
            }
        }
        return true;
    }
    
    
    
}
  /*
    DatasetPage sequence:
    
    (A) editFilesFragment.xhtml -> EditDataFilesPage.handleFileUpload
    (B) EditDataFilesPage.java -> handleFileUpload
        (1) UploadedFile uf  event.getFile() // UploadedFile
            --------
                UploadedFile interface:
                    public String getFileName()
                    public InputStream getInputstream() throws IOException;
                    public long getSize();
                    public byte[] getContents();
                    public String getContentType();
                    public void write(String string) throws Exception;
            --------
        (2) List<DataFile> dFileList = null;     
        try {
            // Note: A single file may be unzipped into multiple files
            dFileList = ingestService.createDataFiles(workingVersion, uFile.getInputstream(), uFile.getFileName(), uFile.getContentType());
        }
    
        (3) processUploadedFileList(dFileList);

    (C) EditDataFilesPage.java -> processUploadedFileList
        - iterate through list of DataFile objects -- which COULD happen with a single .zip
            - isDuplicate check
            - if good:
                - newFiles.add(dataFile);        // looks good
                - fileMetadatas.add(dataFile.getFileMetadata());
            - return null;    // looks good, return null
    (D) save()  // in the UI, user clicks the button.  API is automatic if no errors
        
        (1) Look for constraintViolations:
            // DatasetVersion workingVersion;
            Set<ConstraintViolation> constraintViolations = workingVersion.validate();
                if (!constraintViolations.isEmpty()) {
                 //JsfHelper.addFlashMessage(JH.localize("dataset.message.validationError"));
                 JH.addMessage(FacesMessage.SEVERITY_ERROR, JH.localize("dataset.message.validationError"));
                //FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Validation Error", "See below for details."));
                return "";
            }
    
         (2) Use the ingestService for a final check
            // ask Leonid if this is needed for API
            // One last check before we save the files - go through the newly-uploaded 
            // ones and modify their names so that there are no duplicates. 
            // (but should we really be doing it here? - maybe a better approach to do it
            // in the ingest service bean, when the files get uploaded.)
            // Finally, save the files permanently: 
            ingestService.addFiles(workingVersion, newFiles);

         (3) Use the API to save the dataset
            - make new CreateDatasetCommand
                - check if dataset has a template
            - creates UserNotification message
    
    */  
    // Checks:
    //   - Does the md5 already exist in the dataset?
    //   - If it's a replace, has the name and/or extension changed?
    //   On failure, send back warning
    //
    // - All looks good
    // - Create a DataFile
    // - Create a FileMetadata
    // - Copy the Dataset version, making a new DRAFT
    //      - If it's replace, don't copy the file being replaced
    // - Add this new file.
    // ....
    
    