/**
 * Copyright (c) 2010 Chris Lonnen. All rights reserved.
 *
 * This program and the accompanying materials are made available under the 
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.opensource.org/licenses/eclipse-1.0.php
 * 
 * Contributors:
 *     Chris Lonnen - initial API and implementation
 */
package processing.plugin.ui.wizards;

//import java.util.Iterator;
//import org.eclipse.core.resources.IProject;
//import org.eclipse.core.resources.IResource;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.ui.jarpackager.JarPackageData;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IExportWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;

import processing.plugin.core.ProcessingCore;
import processing.plugin.core.ProcessingLog;
import processing.plugin.core.ProcessingUtilities;
import processing.plugin.core.builder.SketchProject;
import processing.plugin.ui.ProcessingPlugin;


/**
 * An export wizard for processing projects.
 * <p>
 * The single page presents users with a list of open sketch Projects in 
 * the workspace and the user can check one or all of them to export.
 */
public class ExportAsAppletWizard extends Wizard implements IExportWizard {
	
	/** single page */
	private ExportAsAppletSelectProjectsWizardPage page;

	public ExportAsAppletWizard() {}

	/** 
	 * Used to figure out what we're exporting. 
	 * <p>
	 * If more than one item is selected, the first one listed
	 * is the one that gets exported. Just to make sure, the wizard
	 * page prompts with the name of the sketch before continuing. 
	 */
	public void init(IWorkbench workbench, IStructuredSelection selection) {
//		workbench.getActiveWorkbenchWindow().getActivePage();
//		Iterator iter = selection.iterator();
//		while (iter.hasNext()){
//			Object selectedElement = iter.next();
//			if (selectedElement instanceof IProject) {
//				IProject proj = (IProject) selectedElement;
//				if(SketchProject.isSketchProject(proj)){
//
//				}
//			} else if (selectedElement instanceof IResource){
//				IProject proj = ((IResource) selectedElement).getProject();
//				if(SketchProject.isSketchProject(proj)){
//					setProject(proj);
//					break;
//				}
//			}
		// Nowadays the page takes care of it. User selects from whatever is open.
	
	}		

	public void addPages(){
		page = new ExportAsAppletSelectProjectsWizardPage("Export Sketch Wizard");
		addPage(page);
	}

	public boolean performFinish() {
		//return SketchProject.forProject(page.getProject()).exportAsApplet();
		ArrayList<String> couldNotExport = new ArrayList<String>();
		for(SketchProject sp : page.getSelectedProjects()){
			//System.out.println(sp.getProject().getName());
			if (!exportAsApplet(sp)) couldNotExport.add(sp.getProject().getName());
		}
		
		if (couldNotExport.size() > 0)
			for(String s : couldNotExport) ProcessingLog.logInfo( "Unable to export " + s + ".");
			
		return true;
	}
	
	/** 
	 * Tries to export the sketch as an applet, returns whether it was successful.
	 * <p>
	 * This method relies on non-workspace resources, and so invoking it knocks
	 * things out of sync with the file system. It triggers a workspace refresh
	 * of the project after it is finished to realign things.
	 */
	public boolean exportAsApplet(SketchProject sp) {
		if (sp == null) return false;
		if (!sp.getProject().isAccessible()) return false;

		try{
			sp.fullBuild(null);
		} catch (CoreException e){
			ProcessingLog.logError(e);
			return false;
		}
		if (!sp.wasLastBuildSuccessful()){
			ProcessingLog.logError("Could not export " + sp.getProject().getName() + 
					". There were erros building the project.", null);
			return false;
		}
				
		IFile code = sp.getMainFile();
		if (code == null) return false;	
		String codeContents = ProcessingUtilities.readFile(code);
		
		IFolder exportFolder = sp.getAppletFolder(true); // true to nuke the folder contents, if they exist

		HashMap<String,Object> zipFileContents = new HashMap<String,Object>();
		
		// Get size and renderer info from the project
		int wide = sp.getWidth();
		int high = sp.getHeight();
		String renderer = sp.getRenderer();
		
		// Grab the Javadoc-style description from the main code		
		String description ="";
		String[] javadoc = ProcessingUtilities.match(codeContents, "/\\*{2,}(.*)\\*+/");
		if (javadoc != null){
			StringBuffer dbuffer = new StringBuffer();
			String[] pieces = ProcessingUtilities.split(javadoc[1], '\n');
			for (String line : pieces){
				// if this line starts with * characters, remove em
				String[] m = ProcessingUtilities.match(line, "^\\s*\\*+(.*)");
				dbuffer.append(m != null ? m[1] : line);
				dbuffer.append('\n');
			}
			description = dbuffer.toString();
			//System.out.println(description);
		}
		
		// Copy the source files to the target, since we like to encourage people to share their code
		// Get links for each copied code file
		StringBuffer sources = new StringBuffer();
		try{
			for(IResource r : sp.getProject().members()){
				if(!(r instanceof IFile)) continue;
				if(r.getName().startsWith(".")) continue;
				if("pde".equalsIgnoreCase(r.getFileExtension())){
					try{
						r.copy(exportFolder.getFullPath().append(r.getName()), true, null);
						sources.append("<a href=\"" + r.getName() + "\">" +
							r.getName().subSequence(0, r.getName().lastIndexOf(".")-1)
							+ "</a> ");
					} catch (CoreException e) {
						ProcessingLog.logError("Sketch source files could not be included in export of "
						+ sp.getProject().getName() +". Trying to continue export anyway.", e);
					}
				}
			}
		} catch (CoreException e){
			ProcessingLog.logError(e); // problem getting members
		}
		
		// Use separate jarfiles
		boolean separateJar = true; 
		// = Preferences.getBoolean("export.applet.separate_jar_files)||
		// codeFolder.exists() ||
		// (libraryPath.length() != 0);
		
		// Copy the loading gif to the applet
		String LOADING_IMAGE = "loading.gif";
		IFile loadingImage = sp.getProject().getFile(LOADING_IMAGE); // user can specify their own loader
		try {
			loadingImage.copy(exportFolder.getFullPath().append(LOADING_IMAGE), true, null);
		} catch (CoreException e) {
			// This will happen when the copy fails, which we expect if there is no
			// image file. It isn't worth reporting.
			try {
				File exportResourcesFolder = new File(ProcessingCore.getProcessingCore().getPluginResourceFolder().getCanonicalPath(), "export");
				File loadingImageCoreResource = new File(exportResourcesFolder, LOADING_IMAGE);
				ProcessingUtilities.copyFile(loadingImageCoreResource, new File(exportFolder.getLocation().toFile(), LOADING_IMAGE));
			} catch (Exception ex) {
				// This is not expected, and should be reported, because we are about to bail
				ProcessingLog.logError("Could not access the Processing Plug-in Core resources. " +
						"Export aborted.", ex);
				return false;
			}
		}
		
	    // Create new .jar file
	    FileOutputStream zipOutputFile;
		try {
			zipOutputFile = new FileOutputStream(new File(exportFolder.getLocation().toFile(), sp.getProject().getName() + ".jar"));
		} catch (FileNotFoundException fnfe) {
			ProcessingLog.logError(" ",fnfe);
			return false;
		}
	    ZipOutputStream zos = new ZipOutputStream(zipOutputFile);
	    ZipEntry entry;

	    StringBuffer archives = new StringBuffer();
	    archives.append(sp.getProject().getName() + ".jar");		

	    //addmanifest(zos);
	    
		// add the contents of the code folder to the jar
		IFolder codeFolder = sp.getCodeFolder();
		if (codeFolder != null){
			try{
				for(IResource r : codeFolder.members()){
					if(!(r instanceof IFile)) continue;
					if(r.getName().startsWith(".")) continue;
					if ("jar".equalsIgnoreCase(r.getFileExtension()) || 
							"zip".equalsIgnoreCase(r.getFileExtension())){
						r.copy(exportFolder.getFullPath().append(r.getName()), true, null);
	        			//System.out.println("Copied the file to " + exportFolder.getFullPath().toString() + " .");
					}
				}
			} catch (CoreException e){
				ProcessingLog.logError("Code Folder entries could not be included in export." +
						"Export for " + sp.getProject().getName() + " may not function properly.", e);
			}
		}

		// snag the opengl library path so we can test for it later
		File openglLibrary = new File(ProcessingCore.getProcessingCore().getCoreLibsFolder(), "opengl/library/opengl.jar");
		String openglLibraryPath = openglLibrary.getAbsolutePath();
		boolean openglApplet = false;			
		
		// add the library jar files to the folder and detect if opengl is in use
		ArrayList<IPath> sketchLibraryImportPaths = sp.getLibraryPaths();
		if(sketchLibraryImportPaths != null){
			for(IPath path : sketchLibraryImportPaths){
				if (path.toOSString().equals(openglLibraryPath)) openglApplet = true;

				
//				File libraryFolder = new File(path.toOSString());
//				if (path.toOSString().equalsIgnoreCase(openglLibraryPath)) openglApplet=true;
//				File exportSettings = new File(libraryFolder, "export.txt");
//				HashMap<String,String> exportTable = ProcessingUtilities.readSettings(exportSettings);
//				String appletList = (String) exportTable.get("applet");
//				String exportList[] = null;
//				if(appletList != null){
//					exportList = ProcessingUtilities.splitTokens(appletList, ", ");
//				} else {
//					exportList = libraryFolder.list();
//				}
//				for (String s : exportList){
//					if (s.equals(".") || s.equals("..")) continue;
//					
//					s = ProcessingUtilities.trim(s);
//					if (s.equals("")) continue;
//					
//					File exportFile = new File( libraryFolder, s);
//					if(!exportFile.exists()) {
//						ProcessingLog.logError("Export File " + s + " does not exist.", null);
//					} else if (exportFile.isDirectory()) {
//						ProcessingLog.logInfo("Ignoring sub-folder \"" + s + "\"");
//					} else if ( exportFile.getName().toLowerCase().endsWith(".zip") ||
//								exportFile.getName().toLowerCase().endsWith(".jar")){
//						// the PDE checks for separate jar boolean, but if we're here we have
//						// met the conditions that require it
////						File exportFile = new File(codeFolder, s);
//					}
//					
//				}
			}
		}

		// java.io has changed things, so force the workspace to refresh or everything will disappear
		try {
			sp.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);
		} catch (CoreException e) {
			ProcessingLog.logError("The workspace could not refresh after the export wizard ran. " +
					"You may need to manually refresh the workspace to continue.", e);
		}
		
		//DEBUG
		ProcessingLog.logError("Could not export " + sp.getProject().getName() + " because the exporter is not finished.", null);
		return false;
	}

}
