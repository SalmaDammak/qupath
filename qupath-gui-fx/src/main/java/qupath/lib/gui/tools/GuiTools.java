package qupath.lib.gui.tools;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;
import qupath.lib.color.ColorDeconvolutionHelper;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.color.ColorDeconvolutionStains.DefaultColorDeconvolutionStains;
import qupath.lib.color.StainVector;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.roi.PointsROI;

/**
 * Assorted static methods to help with JavaFX and QuPath GUI-related tasks.
 * 
 * @author Pete Bankhead
 *
 */
public class GuiTools {
	
	/**
	 * Kinds of snapshot image that can be created for QuPath.
	 */
	public static enum SnapshotType {
		/**
		 * Snapshot of the current viewer content.
		 */
		VIEWER,
		/**
		 * Snapshot of the full Scene of the main QuPath Window.
		 * This excludes the titlebar and any overlapping windows.
		 */
		MAIN_SCENE,
		/**
		 * Screenshot of the full QuPath window as it currently appears, including any overlapping windows.
		 */
		MAIN_WINDOW_SCREENSHOT,
		/**
		 * Full screenshot, including items outside of QuPath.
		 */
		FULL_SCREENSHOT
	}

	private final static Logger logger = LoggerFactory.getLogger(GuiTools.class);

	/**
	 * Open the directory containing a file for browsing.
	 * @param file
	 * @return
	 */
	public static boolean browseDirectory(final File file) {
		if (file == null || !file.exists()) {
			Dialogs.showErrorMessage("Open", "File " + file + " does not exist!");
			return false;
		}
		if (Desktop.isDesktopSupported()) {
			var desktop = Desktop.getDesktop();
			try {
				// Seems to work on Mac
				if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR))
					desktop.browseFileDirectory(file);
				else {
					// Can open directory in Windows
					if (GeneralTools.isWindows()) {
						if (file.isDirectory())
							desktop.open(file);
						else
							desktop.open(file.getParentFile());
						return true;
					}
					// Trouble on Linux - just copy
					if (Dialogs.showConfirmDialog("Browse directory",
							"Directory browsing not supported on this platform!\nCopy directory path to clipboard instead?")) {
						var content = new ClipboardContent();
						content.putString(file.getAbsolutePath());
						Clipboard.getSystemClipboard().setContent(content);
					}
				}
				return true;
			} catch (Exception e1) {
				Dialogs.showErrorNotification("Browse directory", e1);
			}
		}
		return false;
	}

	/**
	 * Try to open a URI in a web browser.
	 * 
	 * @param uri
	 * @return True if the request succeeded, false otherwise.
	 */
	public static boolean browseURI(final URI uri) {
		return QuPathGUI.launchBrowserWindow(uri.toString());
	}

	/**
	 * Return a result after executing a Callable on the JavaFX Platform thread.
	 * 
	 * @param callable
	 * @return
	 */
	public static <T> T callOnApplicationThread(final Callable<T> callable) {
		if (Platform.isFxApplicationThread()) {
			try {
				return callable.call();
			} catch (Exception e) {
				logger.error("Error calling directly on Platform thread", e);
				return null;
			}
		}
		
		CountDownLatch latch = new CountDownLatch(1);
		ObjectProperty<T> result = new SimpleObjectProperty<>();
		Platform.runLater(() -> {
			T value;
			try {
				value = callable.call();
				result.setValue(value);
			} catch (Exception e) {
				logger.error("Error calling on Platform thread", e);
			} finally {
				latch.countDown();
			}
		});
		
		try {
			latch.await();
		} catch (InterruptedException e) {
			logger.error("Interrupted while waiting result", e);
		}
		return result.getValue();
	}
	
	/**
	 * Run on the application thread and wait until this is complete.
	 * @param runnable
	 */
	public static void runOnApplicationThread(final Runnable runnable) {
		callOnApplicationThread(() -> {
			runnable.run();
			return runnable;
		});
	}
	

	/**
		 * Make a semi-educated guess at the image type of a PathImageServer.
		 * 
		 * @param server
		 * @param imgThumbnail Thumbnail for the image. This is now a required parameter (previously &lt;= 0.1.2 it was optional).
		 * 
		 * @return
		 */
		public static ImageData.ImageType estimateImageType(final ImageServer<BufferedImage> server, final BufferedImage imgThumbnail) {
			
	//		logger.warn("Image type will be automatically estimated");
			
			if (!server.isRGB())
				return ImageData.ImageType.FLUORESCENCE;
			
			BufferedImage img = imgThumbnail;
	//		BufferedImage img;
	//		if (imgThumbnail == null)
	//			img = server.getBufferedThumbnail(220, 220, 0);
	//		else {
	//			img = imgThumbnail;
	//			// Rescale if necessary
	//			if (img.getWidth() * img.getHeight() > 400*400) {
	//				imgThumbnail.getS
	//			}
	//		}
			int w = img.getWidth();
			int h = img.getHeight();
			int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
			long rSum = 0;
			long gSum = 0;
			long bSum = 0;
			int nDark = 0;
			int nLight = 0;
			int n = 0;
			int darkThreshold = 25;
			int lightThreshold = 220;
			for (int v : rgb) {
				int r = ColorTools.red(v);
				int g = ColorTools.green(v);
				int b = ColorTools.blue(v);
				if (r < darkThreshold & g < darkThreshold && b < darkThreshold)
					nDark++;
				else if (r > lightThreshold & g > lightThreshold && b > lightThreshold)
					nLight++;
				else {
					n++;
					rSum += r;
					gSum += g;
					bSum += b;
				}
			}
			if (nDark == 0 && nLight == 0)
				return ImageData.ImageType.UNSET;
			// If we have more dark than light pixels, assume fluorescence
			if (nDark >= nLight)
				return ImageData.ImageType.FLUORESCENCE;
			
	//		Color color = new Color(
	//				(int)(rSum/n + .5),
	//				(int)(gSum/n + .5),
	//				(int)(bSum/n + .5));
	//		logger.debug("Color: " + color.toString());
	
			// Compare optical density vector angles with the defaults for hematoxylin, eosin & DAB
			ColorDeconvolutionStains stainsH_E = ColorDeconvolutionStains.makeDefaultColorDeconvolutionStains(DefaultColorDeconvolutionStains.H_E);
			double rOD = ColorDeconvolutionHelper.makeOD(rSum/n, stainsH_E.getMaxRed());
			double gOD = ColorDeconvolutionHelper.makeOD(gSum/n, stainsH_E.getMaxGreen());
			double bOD = ColorDeconvolutionHelper.makeOD(bSum/n, stainsH_E.getMaxBlue());
			StainVector stainMean = StainVector.createStainVector("Mean Stain", rOD, gOD, bOD);
			double angleH = StainVector.computeAngle(stainMean, stainsH_E.getStain(1));
			double angleE = StainVector.computeAngle(stainMean, stainsH_E.getStain(2));
			ColorDeconvolutionStains stainsH_DAB = ColorDeconvolutionStains.makeDefaultColorDeconvolutionStains(DefaultColorDeconvolutionStains.H_DAB);
			double angleDAB = StainVector.computeAngle(stainMean, stainsH_DAB.getStain(2));
		
			// For H&E staining, eosin is expected to predominate... if it doesn't, assume H-DAB
			logger.debug("Angle hematoxylin: " + angleH);
			logger.debug("Angle eosin: " + angleE);
			logger.debug("Angle DAB: " + angleDAB);
			if (angleDAB < angleE || angleH < angleE) {
				logger.info("Estimating H-DAB staining");
				return ImageData.ImageType.BRIGHTFIELD_H_DAB;
			} else {
				logger.info("Estimating H & E staining");
				return ImageData.ImageType.BRIGHTFIELD_H_E;
			}
		}

	/**
	 * Make a snapshot as a JavaFX {@link Image}, using the current viewer if a viewer is required.
	 * @param qupath
	 * @param type
	 * @return
	 */
	public static WritableImage makeSnapshotFX(final QuPathGUI qupath, final GuiTools.SnapshotType type) {
		return makeSnapshotFX(qupath, qupath.getViewer(), type);
	}

	/**
	 * Make a snapshot as a JavaFX {@link Image}.
	 * @param qupath
	 * @param viewer the viewer to use (or null to use the current viewer)
	 * @param type
	 * @return
	 */
	public static WritableImage makeSnapshotFX(final QuPathGUI qupath, QuPathViewer viewer, final GuiTools.SnapshotType type) {
		if (!Platform.isFxApplicationThread()) {
			var temp = viewer;
			return callOnApplicationThread(() -> makeSnapshotFX(qupath, temp, type));
		}
		Stage stage = qupath.getStage();
		Scene scene = stage.getScene();
		switch (type) {
		case VIEWER:
			if (viewer == null)
				viewer = qupath.getViewer();
			// Temporarily remove the selected border color while copying
			Color borderColor = viewer.getBorderColor();
			try {
				qupath.getViewer().setBorderColor(null);
				return viewer.getView().snapshot(null, null);
			} finally {
				viewer.setBorderColor(borderColor);
			}
		case MAIN_SCENE:
			return scene.snapshot(null);
		case MAIN_WINDOW_SCREENSHOT:
			double x = scene.getX() + stage.getX();
			double y = scene.getY() + stage.getY();
			double width = scene.getWidth();
			double height = scene.getHeight();
			try {
				// For reasons I do not understand, this occasionally throws an ArrayIndexOutOfBoundsException
				return new Robot().getScreenCapture(null,
						x, y, width, height, false);
			} catch (Exception e) {
				logger.error("Unable to make main window screenshot, will resort to trying to crop a full screenshot instead", e);
				var img2 = makeSnapshotFX(qupath, viewer, GuiTools.SnapshotType.FULL_SCREENSHOT);
				return new WritableImage(img2.getPixelReader(), 
						(int)x, (int)y, (int)width, (int)height);
			}
		case FULL_SCREENSHOT:
			var screen = Screen.getPrimary();
			var bounds = screen.getBounds();
			return new Robot().getScreenCapture(null,
					bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
		default:
			throw new IllegalArgumentException("Unknown snaptop type " + type);
		}
	}

	/**
	 * Make a snapshot (image) showing what is currently displayed in a QuPath window
	 * or the active viewer within QuPath, as determined by the SnapshotType.
	 * 
	 * @param qupath
	 * @param type
	 * @return
	 */
	public static BufferedImage makeSnapshot(final QuPathGUI qupath, final GuiTools.SnapshotType type) {
		return SwingFXUtils.fromFXImage(makeSnapshotFX(qupath, qupath.getViewer(), type), null);
	}
	
	/**
	 * Make a BufferedImage snapshot of the specified viewer.
	 * @param viewer
	 * @return
	 */
	public static BufferedImage makeViewerSnapshot(final QuPathViewer viewer) {
		return SwingFXUtils.fromFXImage(makeSnapshotFX(QuPathGUI.getInstance(), viewer, GuiTools.SnapshotType.VIEWER), null);
	}
	
	/**
	 * Make a BufferedImage snapshot of the current GUI.
	 * @return
	 */
	public static BufferedImage makeSnapshot() {
		return SwingFXUtils.fromFXImage(makeSnapshotFX(QuPathGUI.getInstance(), null, GuiTools.SnapshotType.MAIN_SCENE), null);
	}
	
	/**
	 * Make a BufferedImage snapshot of the current viewer.
	 * @return
	 */
	public static BufferedImage makeViewerSnapshot() {
		return SwingFXUtils.fromFXImage(makeSnapshotFX(QuPathGUI.getInstance(), QuPathGUI.getInstance().getViewer(), GuiTools.SnapshotType.VIEWER), null);
	}
	
	/**
	 * Make a BufferedImage snapshot of the full screen.
	 * @return
	 */
	public static BufferedImage makeFullScreenshot() {
		return SwingFXUtils.fromFXImage(makeSnapshotFX(QuPathGUI.getInstance(), null, GuiTools.SnapshotType.FULL_SCREENSHOT), null);
	}

	/**
	 * Get an appropriate String to represent the magnification of the image currently in the viewer.
	 * @param viewer
	 * @return
	 */
	public static String getMagnificationString(final QuPathViewer viewer) {
		if (viewer == null || !viewer.hasServer())
			return "";
//		if (Double.isFinite(viewer.getServer().getMetadata().getMagnification()))
			return String.format("%.2fx", viewer.getMagnification());
//		else
//			return String.format("Scale %.2f", viewer.getDownsampleFactor());
	}

	/**
	 * Prompt user to select all currently-selected objects (except TMA core objects).
	 * 
	 * @param imageData
	 * @return
	 */
	public static boolean promptToClearAllSelectedObjects(final ImageData<?> imageData) {
		// Get all non-TMA core objects
		PathObjectHierarchy hierarchy = imageData.getHierarchy();
		Collection<PathObject> selectedRaw = hierarchy.getSelectionModel().getSelectedObjects();
		List<PathObject> selected = selectedRaw.stream().filter(p -> !(p instanceof TMACoreObject)).collect(Collectors.toList());
	
		if (selected.isEmpty()) {
			if (selectedRaw.size() > selected.size())
				Dialogs.showErrorMessage("Delete selected objects", "No valid objects selected! \n\nNote: Individual TMA cores cannot be deleted with this method.");
			else
				Dialogs.showErrorMessage("Delete selected objects", "No objects selected!");
			return false;
		}
	
		int n = selected.size();
		if (Dialogs.showYesNoDialog("Delete objects", "Delete " + n + " objects?")) {
			// Check for descendants
			List<PathObject> children = new ArrayList<>();
			for (PathObject temp : selected) {
				children.addAll(temp.getChildObjects());
			}
			children.removeAll(selected);
			boolean keepChildren = true;
			if (!children.isEmpty()) {
				Dialogs.DialogButton response = Dialogs.showYesNoCancelDialog("Delete objects", "Keep descendant objects?");
				if (response == Dialogs.DialogButton.CANCEL)
					return false;
				keepChildren = response == Dialogs.DialogButton.YES;
			}
			
			
			hierarchy.removeObjects(selected, keepChildren);
			hierarchy.getSelectionModel().clearSelection();
			imageData.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Delete selected objects", "clearSelectedObjects(" + keepChildren + ");"));
			if (keepChildren)
				logger.info(selected.size() + " object(s) deleted");
			else
				logger.info(selected.size() + " object(s) deleted with descendants");
			imageData.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Clear selected objects", "clearSelectedObjects();"));
			logger.info(selected.size() + " object(s) deleted");
			return true;
		} else
			return false;
	}

	/**
	 * Prompt to remove a single, specified selected object.
	 * 
	 * @param pathObjectSelected
	 * @param hierarchy
	 * @return
	 */
	public static boolean promptToRemoveSelectedObject(PathObject pathObjectSelected, PathObjectHierarchy hierarchy) {
			// Can't delete null - or a TMACoreObject
			if (pathObjectSelected == null || pathObjectSelected instanceof TMACoreObject)
				return false;
			
			// Deselect first
			hierarchy.getSelectionModel().deselectObject(pathObjectSelected);
	
			if (pathObjectSelected.hasChildren()) {
				Dialogs.DialogButton confirm = Dialogs.showYesNoCancelDialog("Delete object", String.format("Keep %d descendant object(s)?", PathObjectTools.countDescendants(pathObjectSelected)));
				if (confirm == Dialogs.DialogButton.CANCEL)
					return false;
				if (confirm == Dialogs.DialogButton.YES)
					hierarchy.removeObject(pathObjectSelected, true);
				else
					hierarchy.removeObject(pathObjectSelected, false);
			} else if (PathObjectTools.hasPointROI(pathObjectSelected)) {
				int nPoints = ((PointsROI)pathObjectSelected.getROI()).getNumPoints();
				if (nPoints > 1) {
					if (!Dialogs.showYesNoDialog("Delete object", String.format("Delete %d points?", nPoints)))
						return false;
					else
						hierarchy.removeObject(pathObjectSelected, false);
				} else
					hierarchy.removeObject(pathObjectSelected, false);	
			} else if (pathObjectSelected.isDetection()) {
				// Check whether to delete a detection object... can't simply be redrawn (like an annotation), so be cautious...
				if (!Dialogs.showYesNoDialog("Delete object", "Are you sure you want to delete this detection object?"))
					return false;
				else
					hierarchy.removeObject(pathObjectSelected, false);
			} else
				hierarchy.removeObject(pathObjectSelected, false);
	//		updateRoiEditor();
	//		pathROIs.getObjectList().remove(pathObjectSelected);
	//		repaint();
			return true;
		}

	/**
	 * Prompt to enter a filename (but not full file path).
	 * This performs additional validation on the filename, stripping out illegal characters if necessary 
	 * and requesting the user to confirm if the result is acceptable or showing an error message if 
	 * no valid name can be derived from the input.
	 * @param title dialog title
	 * @param prompt prompt to display to the user
	 * @param defaultName default name when the dialog is shown
	 * @return the validated filename, or null if the user cancelled or did not provide any valid input
	 * @see GeneralTools#stripInvalidFilenameChars(String)
	 * @see GeneralTools#isValidFilename(String)
	 */
	public static String promptForFilename(String title, String prompt, String defaultName) {
		String name = Dialogs.showInputDialog(title, prompt, defaultName);
		if (name == null)
			return null;
		
		String nameValidated = GeneralTools.stripInvalidFilenameChars(name);
		if (!GeneralTools.isValidFilename(nameValidated)) {
			Dialogs.showErrorMessage(title, name + " is not a valid filename!");
			return null;
		}
		if (!nameValidated.equals(name)) {
			if (!Dialogs.showYesNoDialog(
					"Invalid classifier name", name + " contains invalid characters, do you want to use " + nameValidated + " instead?"))
				return null;
		}
		return nameValidated;
	}

	/**
	 * Try to open a file in the native application.
	 * 
	 * This can be used to open a directory in Finder (Mac OSX) or Windows Explorer etc.
	 * This can however fail on Linux, so an effort is made to query Desktop support and 
	 * offer to copy the path instead of opening the file, if necessary.
	 * 
	 * @param file
	 * @return
	 */
	public static boolean openFile(final File file) {
		if (file == null || !file.exists()) {
			Dialogs.showErrorMessage("Open", "File " + file + " does not exist!");
			return false;
		}
		if (file.isDirectory())
			return browseDirectory(file);
		if (Desktop.isDesktopSupported()) {
			try {
				var desktop = Desktop.getDesktop();
				if (desktop.isSupported(Desktop.Action.OPEN))
					desktop.open(file);
				else {
					if (Dialogs.showConfirmDialog("Open file",
							"Opening files not supported on this platform!\nCopy directory path to clipboard instead?")) {
						var content = new ClipboardContent();
						content.putString(file.getAbsolutePath());
						Clipboard.getSystemClipboard().setContent(content);
					}
				}
				return true;
			} catch (Exception e1) {
				Dialogs.showErrorNotification("Open file", e1);
			}
		}
		return false;
	}

	/**
	 * Paint an image centered within a canvas, scaled to be as large as possible while maintaining its aspect ratio.
	 * 
	 * Background is transparent.
	 * 
	 * @param canvas
	 * @param image
	 */
	public static void paintImage(final Canvas canvas, final Image image) {
		GraphicsContext gc = canvas.getGraphicsContext2D();
		double w = canvas.getWidth();
		double h = canvas.getHeight();
		gc.setFill(Color.TRANSPARENT);
		if (image == null) {
			gc.clearRect(0, 0, w, h);
			return;
		}
		double scale = Math.min(
				w/image.getWidth(),
				h/image.getHeight());
		double sw = image.getWidth()*scale;
		double sh = image.getHeight()*scale;
		double sx = (w - sw)/2;
		double sy = (h - sh)/2;
		gc.clearRect(0, 0, w, h);
		gc.drawImage(image, sx, sy, sw, sh);
	}

}