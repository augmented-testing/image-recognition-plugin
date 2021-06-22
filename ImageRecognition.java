/*
 * Copyright 2021 to Joel Amundberg and Martin Moberg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package plugin;

import com.sun.istack.internal.NotNull;
import eye.Eye;
import eye.Match;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jnativehook.GlobalScreen;
import org.jnativehook.NativeHookException;
import org.jnativehook.keyboard.NativeKeyEvent;
import org.jnativehook.keyboard.NativeKeyListener;
import scout.Action;
import scout.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ImageRecognition {
    private static final Eye EYE = new Eye();
    private static AppState currentState = null;
    private static int selectedScreen = -1;
    private static Rectangle selectedArea;
    private static Rectangle selectedMonitor;
    private static BufferedImage currentScreenshot;
    private static final Logger LOGGER = Logger.getLogger(ImageRecognition.class.getName());
    private static Widget.WidgetType currentWidgetType = Widget.WidgetType.ACTION;
    private static Widget.WidgetSubtype currentWidgetSubtype = Widget.WidgetSubtype.DOUBLE_CLICK_ACTION;
    protected static boolean isControlClicked = false;
    protected static boolean isShiftClicked = false;
    private static long startTypeTime = 0;
    private static Point dragStartPoint = null;
    private static Point dragCurrentPoint = null;
    private static final Color overlayColor = new Color(0, 0, 0, 80);
    private static final Color circleColor = new Color(255, 0, 0, 255);
    private static final Properties keyBindings = new Properties();
    private static int defaultDimensionWidth = 150;
    private static int defaultDimensionHeight = 150;
    private static long mayInsertDragDrop = 0;
    private static final float ONE_MILLION = 1000000.0f;
    private static final LinkedList<AppState> previousState = new LinkedList<>();
    private static boolean shouldInsertState = true;
    private static boolean firstSelected = false;
    private static boolean secondSelected = false;
    private static Point upperLeft = null;
    private static Point lowerRight = null;
    private static Widget latestTypeWidget = null;
    private static Robot robot = null;
    private static ArrayList<GraphicsDevice> graphicDevices = new ArrayList<>();
    private static Widget repairWidget = null;
    private static int minimumMatchPercent = 100;

    /**
     * Delegate method that Scout calls on to start a session.
     */
    public void startSession() {
        /*
            NOTE: We had to manually set SessionState to running.
        */
        StateController.setSessionState(StateController.SessionState.RUNNING);
        currentState = StateController.getCurrentState();

        // Default logging level
        LOGGER.setLevel(Level.INFO);

        // RecognitionMode.EXACT has better performance on larger images.
        EYE.setRecognitionMode(Eye.RecognitionMode.EXACT);

        // Attempt to get the key bindings and apply them.
        getOrCreateKeybindings();

        // Set up the application runtime values.
        minimumMatchPercent =  trySetDefaultIntegers("minmatchpercent", 100);
        defaultDimensionWidth = trySetDefaultIntegers("defaultwidgetwidth", 150);
        defaultDimensionHeight = trySetDefaultIntegers("defaultwidgetheight", 150);

        LOGGER.info("Minimum match = " + minimumMatchPercent + "% | Default widget size = [w="
                + defaultDimensionWidth + ",h=" + defaultDimensionHeight +"]");

        try {
            GlobalScreen.registerNativeHook();

            // GlobalScreen does INFO logs of every action on the system. Remove that by
            // changing the minimum logging level of the class to WARNING
            Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
            logger.setLevel(Level.WARNING);
            logger.setUseParentHandlers(false);
        }
        catch (NativeHookException ex) {
            LOGGER.warning("Failed to add global hook? | " + ExceptionUtils.getStackTrace(ex));
            StateController.displayMessage("Failed to register global hook, stopping session.", 5000);
            StateController.stopSession();
        }

        // Add global key listener.
        GlobalScreen.addNativeKeyListener(new GlobalKeyboardListener());

        // Push the monitor selection prompt.
        try{
            ArrayList<BufferedImage> screenShots = getMonitorScreenshots();
            displayScreenshots(screenShots);
            // Selection prompt within screenshot (monitor)
        }
        catch (Exception e){
            LOGGER.severe("Failed to select screen, dying. | " + ExceptionUtils.getStackTrace(e));
            StateController.displayMessage("Failed to select a screen, session terminated.", 5000);
            StateController.stopSession(); // Let the session die if you can't select a monitor.
        }
    }


    /**
     * Delegate method of Scout that draws shapes on the main canvas.
     * @param g A {@link java.awt.Graphics Graphics} object to be used to draw with.
     */
    public void paintCaptureForeground(Graphics g) {
        if(StateController.isOngoingSession() && !StateController.isToolbarVisible()) {

            if(isControlClicked && isShiftClicked) {
                // early kill, no need to draw.
                if (dragStartPoint == null)
                    return;
                else if (dragCurrentPoint == null) // have gotten the start drag point, but not the current. create min size.
                    dragCurrentPoint = new Point(dragStartPoint.x + 1, dragStartPoint.y + 1);

                // Get the rectangle to draw.
                Rectangle draw = getRectangleFromPoints(dragStartPoint, dragCurrentPoint, true);

                // set rendering hints, color. draw rectangle.
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(overlayColor);
                g2d.fillRect(draw.x, draw.y, draw.width, draw.height);
                g2d.setColor(circleColor);
                g2d.drawOval(draw.x + (draw.width / 2), draw.y + (draw.height / 2), 3, 3);
            }
            else {
                dragStartPoint = null;
                dragCurrentPoint = null;
            }
        }
    }

    /**
     * Helper method to create the {@link java.awt.Rectangle Rectangle} from two points provided.
     * @param scaled Boolean to determine if the coordinates should be scaled to match the main scout window.
     * @return A {@link java.awt.Rectangle Rectangle} with the coordinates to draw the overlay on.
     */
    private Rectangle getRectangleFromPoints(Point p1, Point p2, boolean scaled) {

        int minX = Math.min(p1.x, p2.x);
        int minY = Math.min(p1.y, p2.y);
        int width = Math.abs(p1.x - p2.x);
        int height = Math.abs(p1.y - p2.y);

        if(width == 0)
            width = 1;
        if(height == 0)
            height = 1;

        if(scaled) {
            minX = StateController.getScaledX(minX);
            minY = StateController.getScaledY(minY);
            width = StateController.getScaledX(width);
            height = StateController.getScaledY(height);
        }

        return new Rectangle(minX, minY, width, height);
    }

    /**
     * Helper method to select a sub-region of a screenshot to be displayed.
     * @param screenshot The {@link java.awt.image.BufferedImage screenshot} to make the selection on.
     */
    public void promptSelection(BufferedImage screenshot) {
        JFrame screenShotFrame = new JFrame();
        //BufferedImage minimizedScreenshot = resizeImage(screenshot,StateController.getProductViewWidth(),StateController.getProductViewHeight());
        JLabel temp = new JLabel(new ImageIcon(screenshot));

        screenShotFrame.setLayout(new GridBagLayout());
        screenShotFrame.setSize(screenshot.getWidth(),screenshot.getHeight());
        centreWindow(screenShotFrame);

        temp.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);
                if(!firstSelected) {
                    upperLeft = MouseInfo.getPointerInfo().getLocation();
                    firstSelected = true;
                }
                else if (!secondSelected) {
                    lowerRight = MouseInfo.getPointerInfo().getLocation();
                    secondSelected = true;
                    screenShotFrame.setVisible(false);
                    selectedArea = getRectangleFromPoints(upperLeft, lowerRight, false);
                }
            }
        });
        screenShotFrame.add(temp);
        screenShotFrame.setVisible(true);
    }

    /**
     * Helper method that handles the sleep call and its throws to not have to do it multiple times.
     * @param amountOfMS the amount of milliseconds to sleep for.
     */
    private void sleepForAmountMS(long amountOfMS) {
        long startSleep = System.currentTimeMillis();
        try {
            TimeUnit.MILLISECONDS.sleep(amountOfMS);
        }catch (InterruptedException ex) {
            long amountLeft = System.currentTimeMillis() - startSleep;
            // if it's 10 ms more left to sleep, sleep again.
            if(amountLeft > 10)
                sleepForAmountMS(amountLeft);
            // else fall through and return a bit early
        }
    }

    private Match tryAllThreeModes(Widget toFind) {
        Match match = null;
        Eye.RecognitionMode savedRecognitionMode = EYE.getRecognitionMode();

        EYE.setRecognitionMode(Eye.RecognitionMode.EXACT);
        match = findWidget(toFind);
        if(match == null){
            EYE.setRecognitionMode(Eye.RecognitionMode.COLOR);
            match = findWidget(toFind);
            if(match == null){
                EYE.setRecognitionMode(Eye.RecognitionMode.TOLERANT);
                match = findWidget(toFind);
            }
        }
        else if(match.getMatchPercent() < minimumMatchPercent)
            LOGGER.info("match% tryallthree = " + match.getMatchPercent());

        EYE.setRecognitionMode(savedRecognitionMode);
        return match;
    }

    /**
     * The method that locates, and performs, the actions of the image widgets.
     * @param w The ImageRecognition plugin widget to be performed.
     * @return true if performed, false otherwise.
     */
    private boolean performImageWidget(Widget w) {

        // Init variables
        long startx = System.nanoTime();
        Match match = tryAllThreeModes(w);
        boolean found = true;


        // Handle the matched widgets and perform them.
        if(match != null && match.getMatchPercent() >= minimumMatchPercent) {
            LOGGER.info("Match %"+ match.getMatchPercent());
            w.setLocationArea(new Rectangle(match.getX(), match.getY(), match.getWidth(), match.getHeight()));
            if(w.getWidgetType() == Widget.WidgetType.ACTION && w.getWidgetSubtype() == Widget.WidgetSubtype.DOUBLE_CLICK_ACTION){
                LOGGER.info("Action & Double Click");
                moveMouseAction(w,match.getCenterLocation());
            }
            else if(w.getWidgetType() == Widget.WidgetType.ACTION && w.getWidgetSubtype() == Widget.WidgetSubtype.LEFT_CLICK_ACTION){
                LOGGER.info("Action & Left click");
                moveMouseAction(w,match.getCenterLocation());
            }
            else if(w.getWidgetType() == Widget.WidgetType.ACTION && w.getWidgetSubtype() == Widget.WidgetSubtype.RIGHT_CLICK_ACTION){
                LOGGER.info("Action & Left click");
                moveMouseAction(w,match.getCenterLocation());
            }
            else if(w.getWidgetType() == Widget.WidgetType.ACTION && w.getWidgetSubtype() == Widget.WidgetSubtype.TYPE_ACTION){
                LOGGER.info("Action & Type action");
                moveMouseAction(w,match.getCenterLocation());
            }
            // TODO WE ARE SO SORRY ABOUT THIS - NEED TO REPLACE PASTE_ACTION - TEMPORARY SOLUTION
            else if(w.getWidgetType() == Widget.WidgetType.ACTION && w.getWidgetSubtype() == Widget.WidgetSubtype.PASTE_ACTION) {
                moveMouseAction(w,match.getCenterLocation());
            }
            else if(w.getWidgetType() == Widget.WidgetType.CHECK){
                w.setWidgetStatus(Widget.WidgetStatus.VALID);
            }
        }
        else{
            LOGGER.fine("Didn't find match for widget with image path: " + w.getMetadata("IR_imageName"));
            w.setWidgetStatus(Widget.WidgetStatus.UNLOCATED);
            found = false;
        }


        LOGGER.finer("TIME: [" + ((System.nanoTime() - startx) / ONE_MILLION) + " ms]");

        return found;
    }

    /**
     * Action performer helper method used by {@link #performImageWidget(Widget)}.
     *
     * Determines what action to perform through the widget sent in. The action will be
     * performed at the point specified, and then the mouse will be returned where it
     * started so that the user does not notice the action.
     *
     * @param w The {@link scout.Widget Widget} that is being performed, used to determine what action to take.
     * @param p The {@link java.awt.Point Point} at where the widget should be performed.
     */
    private void moveMouseAction(Widget w, Point p) {
        Point absoluteMousePoint = MouseInfo.getPointerInfo().getLocation();
        robot.mouseMove(p.x + selectedMonitor.x,p.y + selectedMonitor.y);
        if(w.getWidgetSubtype()  == Widget.WidgetSubtype.LEFT_CLICK_ACTION)
            singleLeftClick();
        else if(w.getWidgetSubtype()  == Widget.WidgetSubtype.RIGHT_CLICK_ACTION)
            singleRightClick();
        else if(w.getWidgetSubtype()  == Widget.WidgetSubtype.DOUBLE_CLICK_ACTION)
            doubleClick();
        else if(w.getWidgetSubtype() == Widget.WidgetSubtype.TYPE_ACTION){
            if(w.getMetadata("IR_amountClicksType") != null) {
                int amount = (int)w.getMetadata("IR_amountClicksType");
                if (amount == 1)
                    singleLeftClick();
                else if (amount == 2)
                    doubleClick();
                else if (amount == 3)
                    tripleClick();
                else {
                    LOGGER.warning("Failed to get the amount of clicks?");
                    singleLeftClick();
                }

                typeComment(w);
            }
            else {
                LOGGER.warning("WARNING: ENTERING DEFAULT CASE FOR TYPE ACTION!");

                singleLeftClick();
                typeComment(w);
            }
        }
        else if(w.getWidgetSubtype() == Widget.WidgetSubtype.PASTE_ACTION){
            singleLeftClick(); // Perform first widget click.
            sleepForAmountMS(1500);
            String fileName = (String) w.getMetadata("IR_secondImageWidget");
            if (fileName != null)
            {
                BufferedImage secondImage = EYE.loadImage(getProjectFileLocationForName(fileName));
                Match match = EYE.findImage(currentScreenshot, secondImage);

                if(match != null){

                    robot.mouseMove(match.getCenterLocation().x + selectedMonitor.x,
                            match.getCenterLocation().y + selectedMonitor.y);
                    singleLeftClick(); // Perform second widget click.
                }
                else {
                    LOGGER.info("Failed to find second image of our MENU_CLICK_ACTION (paste_action)");
                }
            }
        }
        else {
            LOGGER.warning("Could not handle subtype: " + w.getWidgetSubtype().toString());
        }

        // Return mouse to original point.
        robot.mouseMove(absoluteMousePoint.x,absoluteMousePoint.y);

    }

    /**
     * Delegate method that Scout calls on to get the image to display in the main window.
     * @return A {@link java.awt.image.BufferedImage BufferedImage} screenshot of the selected monitor/region.
     */
    public BufferedImage getCapture() {
        if(selectedScreen < 0)
            return null;
        else {
            selectedMonitor = graphicDevices.get(selectedScreen).getDefaultConfiguration().getBounds();
        }

        if(isControlClicked && isShiftClicked){
            return currentScreenshot;
        }
        if (lowerRight != null && upperLeft != null && false) { // TODO: Intentionally disabled for now.
            BufferedImage imgTemp = getMonitorScreenshot(selectedMonitor);
            currentScreenshot = EYE.getSubimage(imgTemp,selectedArea.x,selectedArea.y,selectedArea.width,selectedArea.height);

            return currentScreenshot;
        }
        else{
            currentScreenshot = getMonitorScreenshot(selectedMonitor);
            return currentScreenshot;
        }
    }

    /**
     * Delegate method used by Scout to notify the plugins that the state has changed.
     */
    public void changeState() {
        LOGGER.finer("Changed state");

        // Preserve history of previous states.
        // TODO: This is not perfect if manual back/forwards/home-commands are used.
        if(shouldInsertState)
            previousState.push(currentState);
        else
            shouldInsertState = true;

        currentState = StateController.getCurrentState();

        // Force re-check of all widgets on the new state.
        for(Widget w : currentState.getAllWidgets()){
            w.setWidgetStatus(Widget.WidgetStatus.UNLOCATED);
        }

        performAllStateWidgets(MAX_DEPTH, currentState, false);
    }

    private Match findWidget(Widget w){
        String filePath = (String) w.getMetadata("IR_imageName");
        BufferedImage find = EYE.loadImage(getProjectFileLocationForName(filePath));

        if(find != null)
        {
            Match match = EYE.findImage(currentScreenshot, find);
            if(match != null && match.getMatchPercent() >= minimumMatchPercent)
                return match;
            else if(match != null)
                LOGGER.info("Match was not null, but " + match.getMatchPercent() + "% instead of the minimum " +
                        minimumMatchPercent + "%");
        }


        return null;
    }

    /**
     * Helper method to retrieve Type action widgets from
     * @param location The {@link java.awt.Point Point} to look for Type action widgets at.
     * @return The located widget, or null.
     */
    private Widget getTypeWidget(Point location)
    {
        List<Widget> locatedWidgets = StateController.getWidgetsAt(location);
        for(Widget locatedWidget : locatedWidgets)
        {
            if(locatedWidget.getWidgetType() == Widget.WidgetType.ACTION &&
                    locatedWidget.getWidgetSubtype() == Widget.WidgetSubtype.TYPE_ACTION &&
                    locatedWidget.getWidgetVisibility() != Widget.WidgetVisibility.HIDDEN
            )
                    return locatedWidget;
        }
        return null;
    }

    /**
     * Helper method to delete all {@link scout.Widget Widgets} after a specific point on
     * the state graph.
     * @param appState The {@link scout.AppState AppState} to start the deletion from.
     * @param depth Sanity blocker variable to ensure that the recursion does not overflow.
     */
    private void resetFromNode(AppState appState,int depth){
        List <Widget> stateWidgets = appState.getAllWidgets();

        if(depth > 100) // Sanity blocker.
            return;

        for(Widget w: stateWidgets){
            if(w.getNextState() != null && w.getNextState().getAllWidgets().size() > 0)
                resetFromNode(w.getNextState(),++depth);

            deleteFileWithName((String) w.getMetadata("IR_imageName"));
            appState.removeWidget(w);

        }
    }

    /**
     * Helper method to perform Type actions.
     * @param location The {@link java.awt.Point Point} on which to perform the Type action.
     */
    private void performTypeAction(Point location) {
        Widget existingTypeWidget = getTypeWidget(location);

        if(existingTypeWidget != null &&
                StateController.getKeyboardInput().length() > 0 &&
                existingTypeWidget.getWidgetVisibility() == Widget.WidgetVisibility.VISIBLE)
        {
            // Keyboard input on visible widget - add a comment
            if(StateController.getKeyboardInput().endsWith("[ENTER]"))
            {
                StateController.removeLastKeyboardInput();
                StateController.setCurrentState(existingTypeWidget.getNextState());
                existingTypeWidget.setComment(StateController.getKeyboardInput().trim());
                StateController.clearKeyboardInput();
                displayTypeWidgetClickSelection(existingTypeWidget);

                if(latestTypeWidget == existingTypeWidget)
                    latestTypeWidget = null;
            }
        }
        else{
            StateController.displayMessage("Couldn't perform typeAction",1000);
            LOGGER.warning("Failed to perform Type action at " + location.toString());
        }
    }

    /**
     * Helper method that provides a display window to select the amount of clicks to perform when
     * a TYPE widget is being executed.
     * @param w the {@link scout.Widget Widget} that should have the amount of clicks associated.
     */
    private void displayTypeWidgetClickSelection(Widget w) {
        JFrame performFrame = new JFrame();
        performFrame.setLayout(new BoxLayout(performFrame.getContentPane(), BoxLayout.Y_AXIS));

        JLabel attentionTxt = new JLabel("ATTENTION!");
        JLabel infoText = new JLabel("Please select the amount of clicks to perform");
        JButton oneClick = new JButton("ONE CLICK");
        oneClick.setSize(40,40);
        oneClick.addActionListener( x -> handleTypeWidgetClickSelection(x, w, performFrame));

        JButton twoClick = new JButton("TWO CLICKS");
        twoClick.setSize(40,40);
        twoClick.addActionListener( x -> handleTypeWidgetClickSelection(x, w, performFrame));

        JButton threeClick = new JButton("THREE CLICKS");
        threeClick.setSize(40,40);
        threeClick.addActionListener( x -> handleTypeWidgetClickSelection(x, w, performFrame));

        attentionTxt.setAlignmentX(Component.CENTER_ALIGNMENT);
        infoText.setAlignmentX(Component.CENTER_ALIGNMENT);
        oneClick.setAlignmentX(Component.CENTER_ALIGNMENT);
        twoClick.setAlignmentX(Component.CENTER_ALIGNMENT);
        threeClick.setAlignmentX(Component.CENTER_ALIGNMENT);

        performFrame.add(Box.createRigidArea(new Dimension(0, 25)));
        performFrame.add(attentionTxt);
        performFrame.add(infoText);
        performFrame.add(Box.createRigidArea(new Dimension(0, 25)));
        performFrame.add(oneClick);
        performFrame.add(Box.createRigidArea(new Dimension(0, 25)));
        performFrame.add(twoClick);
        performFrame.add(Box.createRigidArea(new Dimension(0, 25)));
        performFrame.add(threeClick);

        performFrame.setSize(250 , 250);
        centreWindow(performFrame);
        performFrame.setVisible(true);
    }

    /**
     * Action handler from amount of clicks selection.
     * @param x The {@link java.awt.event.ActionEvent ActionEvent} that triggered.
     * @param w The {@link scout.Widget Widget} to attach the amount of clicks to.
     * @param f The {@link javax.swing.JFrame JFrame} to dispose of.
     */
    private void handleTypeWidgetClickSelection(ActionEvent x, Widget w, JFrame f) {
        f.dispose();
        JButton source = (JButton) x.getSource();
        if (source != null) {
            switch (source.getText()) {
                case "ONE CLICK":
                    w.putMetadata("IR_amountClicksType", 1);
                    break;
                case "TWO CLICKS":
                    w.putMetadata("IR_amountClicksType", 2);
                    break;
                case "THREE CLICKS":
                    w.putMetadata("IR_amountClicksType", 3);
                    break;
                default:
                    LOGGER.warning("Could not match text to amount clicks, text was: " + source.getText());
                    break;
            }

            performImageWidget(w);
        }

    }

    /**
     * Helper method that manually types strings of text of a widget.
     * @param w The {@link scout.Widget Widget} to use to get the string to type.
     */
    private void typeComment(Widget w){
        String widgetComment = w.getComment();

        if(widgetComment != null)
        {
            for(char c : widgetComment.toCharArray()){
                int keyCode = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c);

                if(keyCode != KeyEvent.VK_UNDEFINED)
                {
                    robot.keyPress(keyCode);
                    sleepForAmountMS(50); // TODO: Is this really necessary?
                    robot.keyRelease(keyCode);
                }
                else
                    LOGGER.info("Failed to type the char [" + c + "]");
            }
        }
        else
            LOGGER.warning("Widget comment was null?");
    }

    /**
     * Helper method to get the full path to the image saving directory with the image name provided.
     * @param fileName The name of the image to be saved.
     * @return Full {@link java.lang.String String} of the path if successful, otherwise null.
     */
    private String getProjectFileLocationForName(String fileName) {
        try {
            String path = "./data/";
            path += StateController.getProduct();
            path += "/images/";

            if(!Files.exists(Paths.get(path)))
                Files.createDirectories(Paths.get(path));
                return path + fileName;

        } catch (Exception e) {
            LOGGER.warning("Could not access/create path - error: " + ExceptionUtils.getStackTrace(e));
            return null;
        }


    }

    /**
     * Helper method to delete a specific file from the project image directory.
     * @param fileName The name of the file to delete.
     * @return True if successfully deleted, otherwise False.
     */
    private boolean deleteFileWithName(String fileName) {

        if(fileName.contains("..")) {
            LOGGER.severe("Do not manipulate the path structure with the file name!");
            return false;
        }

        String fullPath = getProjectFileLocationForName(fileName);

        if(fullPath != null) {
            File toDelete = new File(fullPath);

            try {
                if(toDelete.delete()) {
                    LOGGER.fine("Deleted file with path - " + fullPath);
                    return true;
                }
                else {
                    LOGGER.warning("Failed to delete file with path - " + fullPath);
                    return false;
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to delete file with path - Security exception - "
                        + fullPath + " - " + ExceptionUtils.getStackTrace(e));
                return false;
            }
        }
        else {
            LOGGER.warning("Failed to get full path?");
            return false;
        }
    }

    /**
     * Helper method to execute having confirmed the choice to delete a widget.
     * @param w The {@link scout.Widget Widget} to delete the tree from.
     * @param f The {@link javax.swing.JFrame JFrame} to dispose after making the selection.
     */
    private void confirmDeleteSelection(Widget w, JFrame f){
        if(w == latestTypeWidget)
            latestTypeWidget = null;
        else if (w == repairWidget)
            repairWidget = null;

        resetFromNode(w.getNextState(),0);
        deleteFileWithName((String) w.getMetadata("IR_imageName"));
        StateController.getCurrentState().removeWidget(w);
        f.dispose();
    }

    /**
     * Helper method to get the KeyCode for a property with the name of {@link java.lang.String key}.
     * @param key The {@link java.lang.String String} name of the key to get.
     * @return The {@link java.awt.event.KeyEvent KeyEvent} integer ID code.
     */
    private int getKeybindingKeyCode(String key){
        String keyBindingString = keyBindings.getProperty(key);
        if(keyBindingString != null)
            return java.awt.event.KeyEvent.getExtendedKeyCodeForChar(keyBindingString.charAt(0));
        else {
            LOGGER.warning("Failed to get property with the name: [" + key + "]");
            return KeyEvent.VK_UNDEFINED;
        }

    }

    /**
     * Method for handling the delegated actions from the main Scout application.
     * This is the main workhorse of the plugin as it reacts to all events from Scout.
     * @param action The {@link scout.Action action} that has been delegated.
     */
    public void performAction(Action action) {
        if (!StateController.isRunningSession() || action.isToolbarAction())
            return;

        if (action instanceof TypeAction) {
            TypeAction typeAction = (TypeAction) action;
            KeyEvent keyEvent = typeAction.getKeyEvent();
            int keyCode = keyEvent.getKeyCode();
            char keyChar = keyEvent.getKeyChar();

            if (keyCode == KeyEvent.VK_ENTER) {
                /* Send keyboardInput to hovered widget (if any) */
                StateController.addKeyboardInput("[ENTER]");
                performTypeAction(typeAction.getLocation());
            } else if (keyCode == KeyEvent.VK_ESCAPE) {
                /* Cancel text input */
                if (StateController.getKeyboardInput().length() > 0) {
                    StateController.clearKeyboardInput();
                }
                if (repairWidget != null) {
                    repairWidget = null;
                } else if(menuWidget != null) {
                    StateController.getCurrentState().removeWidget(menuWidget);
                    menuWidget = null;
                } else if(latestTypeWidget != null) {
                    StateController.getCurrentState().removeWidget(latestTypeWidget);
                    latestTypeWidget = null;
                }
            } else if (!isControlClicked && keyCode == KeyEvent.VK_BACK_SPACE) {
                if(StateController.getKeyboardInput().length() > 0)
                    StateController.removeLastKeyboardInput();
            } else if (!isControlClicked && keyCode == KeyEvent.VK_SPACE) {
                StateController.addKeyboardInput(" ");
            }
            else if (isControlClicked && keyCode == getKeybindingKeyCode("performwidgets")) {
                /* Perform the entire state tree automatically from where you are */
                LOGGER.info("Performing every widget automatically from the current app state.");
                displayPerformWidgets(currentState);
            } else if (isControlClicked && keyCode == getKeybindingKeyCode("type")) {
                /* Keybinding for the Type action widget */
                currentWidgetSubtype = Widget.WidgetSubtype.TYPE_ACTION;
                currentWidgetType = Widget.WidgetType.ACTION;
                announceCurrentWidgetTypes();
                LOGGER.info("Type: " + currentWidgetType + "\nAction: " + currentWidgetSubtype);
            } else if (isControlClicked && keyCode == getKeybindingKeyCode("leftclick")) {
                /* Keybinding for the Left Click action widget */
                currentWidgetSubtype = Widget.WidgetSubtype.LEFT_CLICK_ACTION;
                currentWidgetType = Widget.WidgetType.ACTION;
                announceCurrentWidgetTypes();
                LOGGER.info("Type: " + currentWidgetType + "\nAction: " + currentWidgetSubtype);
            } else if (isControlClicked && keyCode == getKeybindingKeyCode("rightclick")) {
                /* Keybinding for the right click action widget */
                currentWidgetSubtype = Widget.WidgetSubtype.RIGHT_CLICK_ACTION;
                currentWidgetType = Widget.WidgetType.ACTION;
                announceCurrentWidgetTypes();
                LOGGER.info("Type: " + currentWidgetType + "\nAction: " + currentWidgetSubtype);
            } else if (isControlClicked && keyCode == getKeybindingKeyCode("check")) {
                /* Keybinding for the Check widget */
                currentWidgetType = Widget.WidgetType.CHECK;
                currentWidgetSubtype = null; // Check has no subtype.
                announceCurrentWidgetTypes();
                LOGGER.info("Type: " + currentWidgetType + "\nAction: " + currentWidgetSubtype);
            } else if (isControlClicked && keyCode == getKeybindingKeyCode("doubleclick")) {
                /* Keybinding for the Double Click action widget */
                //NOTE: Creates unknown hover action due to not being implemented in the HoverAction plugin
                currentWidgetSubtype = Widget.WidgetSubtype.DOUBLE_CLICK_ACTION;
                currentWidgetType = Widget.WidgetType.ACTION;
                announceCurrentWidgetTypes();
                LOGGER.info("Type: " + currentWidgetType.toString() + "\nAction: " + currentWidgetSubtype.toString());
            } else if (isControlClicked && keyCode == getKeybindingKeyCode("menuaction")) {
                /* Keybinding for Menu Click actions */
                currentWidgetSubtype = Widget.WidgetSubtype.PASTE_ACTION; // TODO WE ARE SORRY ABOUT THIS, TEMPORARY SOLUTION
                currentWidgetType = Widget.WidgetType.ACTION;
                announceCurrentWidgetTypes();
                LOGGER.info("Type: " + currentWidgetType.toString() + "\nAction: " + currentWidgetSubtype.toString());
            }
            else if(isControlClicked && keyCode == KeyEvent.VK_K) {
                LOGGER.info("Displaying Keybinding form");
                showKeybindingForm();
            }
            else if(isControlClicked && keyCode == getKeybindingKeyCode("forcerepair")) {
                LOGGER.info("Force repair widget");
                Point location = ((TypeAction) action).getLocation();
                List<Widget> foundWidgets = StateController.getWidgetsAt(location);
                if (!foundWidgets.isEmpty()) {
                    for (Widget w : foundWidgets) {
                        if (w.getWidgetVisibility() == Widget.WidgetVisibility.VISIBLE && w.getWidgetStatus() == Widget.WidgetStatus.LOCATED) {
                            BufferedImage img = EYE.loadImage(getProjectFileLocationForName((String)w.getMetadata("IR_imageName")));
                            displayImageFrame(w, img);
                        }
                    }
                } // else
                // Do nothing, did not click on a widget.
            }
            else if(isControlClicked && keyCode == getKeybindingKeyCode("home")){
                LOGGER.info("Went to the Home node.");
                currentState = StateController.getStateTree();
                previousState.clear(); // Reset the previous states when you go home.
                StateController.setCurrentState(currentState);
                StateController.displayMessage("Went to Home node.", 1000);
            }
            else if(isControlClicked && keyCode == KeyEvent.VK_BACK_SPACE){
                /* Remove widget at mouse pointer and all branches underneath it */
                Point location = ((TypeAction) action).getLocation();
                List<Widget> foundWidgets = StateController.getWidgetsAt(location);

                if(!foundWidgets.isEmpty()) {
                    Widget selected = foundWidgets.get(StateController.getSelectedWidgetNo());
                    String filePath = (String) selected.getMetadata("IR_imageName");
                    displayDeletePrompt(selected, filePath);
                }
                else
                    LOGGER.fine("Failed to locate widgets to delete at point: " + location.toString());

            }
            else if(isControlClicked && keyCode == getKeybindingKeyCode("previousstate")){
                /* Go back a step in the state graph. Utilize pre-built list of actions for this purpose. */
                if(!previousState.isEmpty()){
                    shouldInsertState = false;
                    StateController.setCurrentState(previousState.pop());
                    StateController.displayMessage("Went to previous node.", 1000);
                }
                else
                    LOGGER.info("previousState was empty, going back failed.");

            }
            else if(isControlClicked && keyCode == getKeybindingKeyCode("nextstate")){
                /* Go forward a step in the state graph. Utilize pre-built list of actions for this purpose. */
                Widget w = currentState.getAllWidgets().get(0);
                if(w.getNextState() != null){
                    currentState = w.getNextState();
                    shouldInsertState = false;
                    StateController.setCurrentState(currentState);
                    StateController.displayMessage("Went to next node.", 1000);
                }
                else
                    LOGGER.info("nextState was empty, going forward failed.");
            }
            else {
                /* Catch-all for all other typing actions */
                if (StateController.getKeyboardInput().length() == 0)
                    startTypeTime = System.currentTimeMillis(); // First typed char - remember the time
                StateController.addKeyboardInput(getKeyText(keyChar));
            }
        } else if (action instanceof LeftClickAction && isControlClicked) {
            long startAddWidget = System.nanoTime();

            // Create widgetImage location rectangle
            Point p = ((MoveAction) action).getLocation();
            int minX = Math.max((int)(p.x - (defaultDimensionWidth /2.0f)), 0);
            int minY = Math.max((int)(p.y - (defaultDimensionHeight /2.0f)), 0);
            BufferedImage find = getWidgetImage(new Rectangle(minX, minY, defaultDimensionWidth, defaultDimensionHeight));

            // Attempt to locate the image (for verification purposes)
            Match match = EYE.findImage(currentScreenshot, find);

            if (match != null) {
                //robot.mouseMove(upperLeft.x + p.x + selectedMonitor.x,lowerRight.y + p.y + selectedMonitor.y);
                if(!createAndAddWidget(match, find)) {
                    StateController.displayMessage("Failed to add widget");
                    LOGGER.warning("Failed to add widget? Match was not null.");
                }
                else
                    LOGGER.info("Inserted widget, it took [" + ((System.nanoTime() - startAddWidget) / ONE_MILLION)   + "ms]");
            } else {
                LOGGER.info("CLICK: Match is null!");
            }
        } else if (action instanceof LeftClickAction) {
            Point location = ((MoveAction) action).getLocation();
            List<Widget> foundWidgets = StateController.getWidgetsAt(location);
            if (!foundWidgets.isEmpty()) {
                for (Widget w : foundWidgets) {
                    if (w.getWidgetVisibility() == Widget.WidgetVisibility.VISIBLE && w.getWidgetStatus() == Widget.WidgetStatus.LOCATED && w.getWidgetType() == Widget.WidgetType.ACTION) {
                        int findWidgetIterations;
                        try {
                            findWidgetIterations = Integer.parseInt(keyBindings.getProperty("widgetfindretries","5"));
                        } catch (NumberFormatException e) {
                            LOGGER.warning("Int parse error in widgetfindretries");
                            findWidgetIterations = 5;
                        }
                        for(int i = 0; i < findWidgetIterations; i++) {
                            if(performImageWidget(w)) {
                                StateController.setCurrentState(w.getNextState());
                                break;
                            }
                            else {
                                LOGGER.info("Fail, retry after sleep.");
                                sleepForAmountMS(400);
                            }
                        }
                        break; // Only perform one action widget in a stack.
                    } else if (w.getWidgetVisibility() == Widget.WidgetVisibility.VISIBLE && w.getWidgetStatus() == Widget.WidgetStatus.UNLOCATED) {
                        String fileName = (String) w.getMetadata("IR_imageName");
                        BufferedImage widgetImage = EYE.loadImage(getProjectFileLocationForName(fileName));

                        if(widgetImage != null)
                            displayImageFrame(w, widgetImage);
                        else
                            LOGGER.info("Stored file path [" + fileName + "] returned null for image.");
                    }
                }
            } // else
                // Do nothing, did not click on a widget.
        }
        else if (action instanceof DragStartAction && isControlClicked) {
            if(dragStartPoint == null)
                dragStartPoint = ((MoveAction)action).getLocation();
            else
                LOGGER.info("DragStartPoint wasn't null...");
        }
        else if (action instanceof DragAction) {
            if(isControlClicked){
                dragCurrentPoint = ((MoveAction)action).getLocation();
            }
            else{
                dragCurrentPoint = null;
                dragStartPoint = null;
            }
        }
        else if (action instanceof DragDropAction && isControlClicked) {
            if(System.currentTimeMillis() < mayInsertDragDrop + 500)
                return;
            long startInsert = System.nanoTime();
            mayInsertDragDrop = System.currentTimeMillis(); // Blocking variable to prevent multiple inserts.

            // Get sub-image of screenshot
            Rectangle area = getRectangleFromPoints(dragStartPoint, dragCurrentPoint, false);
            BufferedImage find = getWidgetImage(area);

            if (find != null) {
                // Locate the sub-image for verification purposes
                Match match = EYE.findImage(currentScreenshot, find);
                if(match != null && match.getMatchPercent() >= minimumMatchPercent) {
                    // Add widget
                    if(!createAndAddWidget(match, find)) {
                        StateController.displayMessage("Failed to add widget?", 1000);
                        LOGGER.warning("Failed to add widget from rectangle even if match != null?");
                    }
                    else
                        LOGGER.info("Inserted widget, it took [" + ((System.nanoTime() - startInsert) / ONE_MILLION)   + "ms]");
                }
                else if(match != null) {
                    LOGGER.info("DragDrop: Match was not null, but match percent was [" + match.getMatchPercent() + "]");
                }
                else {
                    LOGGER.info("DragDrop: Match was null on rectangle!");
                }
            }

            // null out the drag points as the action is completed
            dragStartPoint = null;
            dragCurrentPoint = null;
        } else if (action instanceof MouseScrollAction) {
            MouseScrollAction mouseScrollAction = (MouseScrollAction) action;
            StateController.setSelectedWidgetNo(StateController.getSelectedWidgetNo() + mouseScrollAction.getRotation());
        } else {
            LOGGER.finest("Error in handling action [" + action.toString() +"]");
        }
    }

    /**
     * Helper method to display an image in a window.
     * @param widgetImage The {@link java.awt.image.BufferedImage BufferedImage} to display.
     */
    private void displayImageFrame(Widget w,BufferedImage widgetImage) {
        JFrame imageFrame = new JFrame();
        JLabel image = new JLabel(new ImageIcon(widgetImage));
        JButton repairBtn = new JButton("Repair");
        repairBtn.setSize(40,40);
        repairBtn.addActionListener( x -> attemptRepairWidget(imageFrame,w));
        GridBagConstraints gbc = new GridBagConstraints();
        imageFrame.setLayout(new GridBagLayout());

        imageFrame.setLayout(new GridBagLayout());
        imageFrame.setLayout(new GridBagLayout());
        // Build frame layout
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        imageFrame.add(image,gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        imageFrame.add(repairBtn,gbc);

        imageFrame.setSize(widgetImage.getWidth() + 60 , widgetImage.getHeight() + 60);
        centreWindow(imageFrame);
        imageFrame.setVisible(true);
    }

    /**
     * Helper method to display a notice windows about releasing the CTRL key before starting
     * to auto-run all the widgets from the specified state.
     * @param state The {@link scout.AppState AppState} to auto-run from.
     */
    private void displayPerformWidgets(AppState state) {
        JFrame performFrame = new JFrame();
        performFrame.setLayout(new BoxLayout(performFrame.getContentPane(), BoxLayout.Y_AXIS));

        JLabel warningText = new JLabel("ATTENTION!");
        JLabel infoText = new JLabel("Please release CTRL and then press START");
        JButton startBtn = new JButton("START");
        startBtn.setSize(40,40);
        startBtn.addActionListener( x -> confirmPerformWidgets(performFrame, state));

        warningText.setAlignmentX(Component.CENTER_ALIGNMENT);
        startBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        infoText.setAlignmentX(Component.CENTER_ALIGNMENT);

        performFrame.add(Box.createRigidArea(new Dimension(0, 25)));
        performFrame.add(warningText);
        performFrame.add(infoText);
        performFrame.add(Box.createRigidArea(new Dimension(0, 25)));
        performFrame.add(startBtn);

        performFrame.setSize(250 , 175);
        centreWindow(performFrame);
        performFrame.setVisible(true);
    }

    /**
     * Helper method to perform all widgets after confirmation.
     * @param f The {@link javax.swing.JFrame JFrame} to dispose.
     * @param state The {@link scout.AppState AppState} to auto-run from.
     */
    private void confirmPerformWidgets(JFrame f, AppState state) {
        f.dispose();
        performAllStateWidgets(0, state, true);
    }

    /**
     * Helper method to set up the pre-conditions of repairing a widget.
     * @param imageFrame The {@link javax.swing.JFrame frame}to dispose.
     * @param w The {@link scout.Widget widget} to repair.
     */
    private void attemptRepairWidget(JFrame imageFrame,Widget w){
        // Set "action" mode to widget type and subtype
        StateController.displayMessage("Perform widget action as intended. Action type switched.", 2000);
        repairWidget = w;
        currentWidgetType = repairWidget.getWidgetType();
        currentWidgetSubtype = repairWidget.getWidgetSubtype();
        imageFrame.dispose();
    }

    /**
     * Helper method to display the {@link scout.Widget Widget} deletion prompt.
     * @param w The {@link scout.Widget Widget} to delete.
     * @param filePath The file path of
     */
    private void displayDeletePrompt(Widget w, String filePath) {
        JLabel image = new JLabel("DEFAULT");
        BufferedImage widgetImage = EYE.loadImage(getProjectFileLocationForName(filePath));

        if(widgetImage != null)
            image = new JLabel(new ImageIcon(widgetImage));
        else
            LOGGER.info("Failed to load image at path [" + filePath + "]");

        // Init buttons, frame. Load image to display what is being deleted.
        JFrame confirmFrame = new JFrame("Are you sure?");
        JButton yesButton = new JButton("Yes");
        JButton noButton = new JButton("No");
        String widgetInfoString = "Type: " + w.getWidgetType() + " SubType: " + w.getWidgetSubtype();
        JLabel actionLabel = new JLabel(widgetInfoString);
        GridBagConstraints gbc = new GridBagConstraints();

        // Bind actions to the buttons
        yesButton.addActionListener(x -> confirmDeleteSelection(w,confirmFrame));
        noButton.addActionListener(x -> confirmFrame.dispose());
        confirmFrame.setLayout(new GridBagLayout());

        // Build frame layout
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        confirmFrame.add(image,gbc);
        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        confirmFrame.add(actionLabel,gbc);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        confirmFrame.add(yesButton,gbc);
        gbc.gridx = 1;
        gbc.gridy = 2;
        confirmFrame.add(noButton,gbc);
        yesButton.setPreferredSize(new Dimension(60,40));
        noButton.setPreferredSize(new Dimension(60,40));
        confirmFrame.setSize(widgetImage.getWidth() + 200,widgetImage.getHeight() + 200);
        centreWindow(confirmFrame);
        confirmFrame.setVisible(true);
    }

    /**
     * Helper method to announce what types of widgets are being created.
     */
    private void announceCurrentWidgetTypes() {
        StateController.displayMessage("Currently performing: MAIN TYPE: ["
                + currentWidgetType + "] SUBTYPE: [" + currentWidgetSubtype +"]", 2000);
    }

    private static Widget menuWidget = null;

    /**
     * Helper method to create and add a widget to Scout and the state graph.
     * @param match The {@link Match Match} object that describes where the widget is located.
     * @return True if widget was added, otherwise False.
     */
    private boolean createAndAddWidget(@NotNull Match match, @NotNull BufferedImage found) {
        try {
            // Create timestamp for image naming.
            long timeStamp = System.currentTimeMillis();
            String fileName = String.format("%d.png", timeStamp);
            Eye.savePngImage(found, getProjectFileLocationForName(fileName));

            // If repairing do this instead
            if(repairWidget != null){
                repairWidget.putMetadata("IR_imageName", fileName);
                repairWidget.setWidgetType(currentWidgetType);
                repairWidget.setWidgetSubtype(currentWidgetSubtype);

                if(currentWidgetType == Widget.WidgetType.ACTION && currentWidgetSubtype == Widget.WidgetSubtype.PASTE_ACTION)
                {
                    menuWidget = repairWidget;
                }

                repairWidget.setLocationArea(new Rectangle(match.getX(), match.getY(), match.getWidth(), match.getHeight()));
                putWidgetMetaData(repairWidget, match);
                repairWidget = null;
                LOGGER.info("Repaired widget.");
                return true;
            }
            else if (menuWidget != null) {
                menuWidget.putMetadata("IR_secondImageWidget", fileName);
                performImageWidget(menuWidget);
                menuWidget = null;
                return true;
            }

            Widget widget = new Widget();
            widget.setWidgetType(currentWidgetType);
            widget.setWidgetSubtype(currentWidgetSubtype);
            AppState appState = StateController.getCurrentState();
            widget.putMetadata("IR_imageName", fileName);
            widget.setLocationArea(new Rectangle(match.getX(), match.getY(), match.getWidth(), match.getHeight()));
            putWidgetMetaData(widget, match);

            // Set action and type of widget
            if(widget.getWidgetSubtype() == Widget.WidgetSubtype.TYPE_ACTION && widget.getWidgetType() == Widget.WidgetType.ACTION){
                if(latestTypeWidget != null) {
                    StateController.displayMessage("There already is a Type action in progress, finish that first.", 5000);
                    return false;
                }
                latestTypeWidget = widget;
            }
            else if(latestTypeWidget != null){
                currentState.removeWidget(latestTypeWidget);
                latestTypeWidget = null;
            }

            AppState newState = StateController.insertWidget(appState, widget, widget.getNextState(), StateController.getProductVersion(),
                    StateController.getTesterName(), StateController.getCurrentPath());
            if (newState != null) {
                if (widget.getWidgetType() == Widget.WidgetType.ACTION && widget.getWidgetSubtype() == Widget.WidgetSubtype.PASTE_ACTION)
                    menuWidget = widget;

                if (widget.getWidgetType() == Widget.WidgetType.ACTION && widget.getWidgetSubtype() != Widget.WidgetSubtype.TYPE_ACTION) {
                    StateController.setCurrentState(newState); //widget.getNextState()
                    moveMouseAction(widget, match.getCenterLocation());
                }
            }
            else {
                LOGGER.warning("StateController.insertWidget returned NULL on insert??");
                StateController.displayMessage("Failed to insert widget due to StateController.");
                return false;
            }
            return true;
        } catch (Exception e) {
            LOGGER.warning("Failed to add widget. | " + ExceptionUtils.getStackTrace(e));
            return false;
        }
    }

    /**
     * Perform all widgets from the state provided and onwards, recursively, automatically.
     * @param depth Integer sanity value, start at 0.
     * @param workState The {@link scout.AppState AppState} to work from.
     */
    private static final int MAX_DEPTH = 100;
    public boolean performAllStateWidgets(int depth, AppState workState, boolean perform) {

        if(depth > MAX_DEPTH) // sanity blocker
            return false;

        ArrayList<Widget> tempActionWidgets = new ArrayList<>();
        ArrayList<Widget> unlocatedWidgets = new ArrayList<>();
        boolean keepIterating = true;
        boolean shouldPerform = true;
        int findWidgetIterations = Integer.parseInt(keyBindings.getProperty("widgetfindretries","5"));

        StateController.setCurrentState(workState);
        List<Widget> widgetList = workState.getAllWidgets();

        if(widgetList.isEmpty()) // No need to do any checks on 0 widgets
            return true;

        // Retry matching on failed widgets (due to interface load times)
        while(keepIterating && findWidgetIterations-- > 0) {
            LOGGER.info(findWidgetIterations + " tries left to find a Widget.");


            for(Widget wid : widgetList)
            {
                if(wid.getWidgetType() == Widget.WidgetType.ACTION) {
                    if(wid.getWidgetStatus() == Widget.WidgetStatus.UNLOCATED) {
                        Match match = tryAllThreeModes(wid);
                        if(match == null || match.getMatchPercent() < minimumMatchPercent) {
                            unlocatedWidgets.add(wid);
                        }
                        else if (match != null && match.getMatchPercent() >= minimumMatchPercent) {
                            wid.setWidgetStatus(Widget.WidgetStatus.LOCATED);
                        }
                    }

                    if(!tempActionWidgets.contains(wid))
                        tempActionWidgets.add(wid);
                }
                else if (wid.getWidgetStatus() == Widget.WidgetStatus.UNLOCATED) {
                    if(!performImageWidget(wid)) {
                        unlocatedWidgets.add(wid);
                    }
                    else {
                        wid.setWidgetStatus(Widget.WidgetStatus.VALID);
                    }
                }
            }

            if(unlocatedWidgets.size() > 0) {
                unlocatedWidgets.clear(); 
                sleepForAmountMS(400);
                shouldPerform = false;
            }
            else {
                keepIterating = false;
                shouldPerform = true;
            }

        }

        if(perform && shouldPerform) {
            for(Widget tempPerformWidget : tempActionWidgets) {
                keepIterating = performImageWidget(tempPerformWidget);
                sleepForAmountMS(500);

                if(keepIterating)
                    keepIterating = performAllStateWidgets(++depth, tempPerformWidget.getNextState(), true);
                else {
                    LOGGER.info("Failed to locate widget, stopping.");

                    break;
                }
            }
        }

        return keepIterating;
    }

    /**
     * Helper method to perform a single Left Mouse Button click.
     */
    private void singleLeftClick() {
        robot.mousePress(InputEvent.BUTTON1_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_MASK);
    }
    /**
     * Helper method to perform a single Right Mouse Button click.
     */
    private void singleRightClick() {
        robot.mousePress(InputEvent.BUTTON3_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_MASK);
    }

    /**
     * Helper method to perform a Double Left Click.
     */
    private void doubleClick(){
        singleLeftClick();
        sleepForAmountMS(100);
        singleLeftClick();
    }

    /**
     * Helper method to perform a Triple Left Click.
     */
    private void tripleClick(){
        singleLeftClick();
        sleepForAmountMS(100);
        singleLeftClick();
        sleepForAmountMS(100);
        singleLeftClick();
    }

    /**
     * Helper method to store widget metadata.
     * @param w The {@link scout.Widget Widget} to store the metadata on.
     * @param m The {@link Match Match} to retrieve the metadata from
     */
    public void putWidgetMetaData(Widget w,Match m){
        w.setWidgetVisibility(Widget.WidgetVisibility.VISIBLE);
        w.setWidgetStatus(Widget.WidgetStatus.LOCATED);
        w.putMetadata("IR_x", m.getX());
        w.putMetadata("IR_y", m.getY());
        w.putMetadata("IR_width", m.getWidth());
        w.putMetadata("IR_height", m.getHeight());
        w.putMetadata("IR_centerPosX", m.getCenterLocation().x);
        w.putMetadata("IR_centerPosY", m.getCenterLocation().y);
    }

    /**
     * Helper method to get a smaller sub-image from the {@link #currentScreenshot}.
     * @param area The {@link java.awt.Rectangle Rectangle} of the new image within the larger one.
     * @return The newly created image. Returns null if it failed to create an image.
     */
    private BufferedImage getWidgetImage(Rectangle area) {
        BufferedImage widgetImage = EYE.getSubimage(currentScreenshot, area.x, area.y,area.width,area.height);

        if(widgetImage == null) {
            StateController.displayMessage("ERROR: WidgetImage was null. Check logs.", 1000);
            LOGGER.warning("ERROR: WidgetImage was null on capture. Rectangle specs: " + area.toString());
        }

        return widgetImage;
    }

    /**
     * Helper method to get a screenshot from each of the available monitors.
     * @return An {@link java.util.ArrayList ArrayList} of {@link java.awt.image.BufferedImage BufferedImage}s
     */
    private ArrayList<BufferedImage> getMonitorScreenshots() {
        if(graphicDevices.isEmpty())
            graphicDevices.addAll(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()));

        Rectangle screenRect;
        ArrayList<BufferedImage> screenShots = new ArrayList<>();

        for (GraphicsDevice gd : graphicDevices) {
            screenRect = gd.getDefaultConfiguration().getBounds();
            screenShots.add(getMonitorScreenshot(screenRect));
        }

        return screenShots;
    }

    /**
     * Helper method to get a specific monitors screenshot.
     * @param rect The monitor bounds in the form of a {@link java.awt.Rectangle Rectangle}.
     * @return A {@link java.awt.image.BufferedImage BufferedImage} of the screen defined by the rectangle. Returns null if
     * robot fails to instantiate.
     */
    private BufferedImage getMonitorScreenshot(Rectangle rect) {
        if (robot == null){
            try{
                robot = new Robot();
            }
            catch (Exception e){
                LOGGER.severe("Failed to instantiate a Robot instance? | " + ExceptionUtils.getStackTrace(e));
                StateController.displayMessage("Failed to instantiate a Robot instance, stopping session.", 5000);
                StateController.stopSession();
                return null;
            }
        }
        return robot.createScreenCapture(rect);
    }

    /**
     * Helper method to display a window that lets you select a screen to work against.
     * @param screenShots An {@link java.util.ArrayList ArrayList} of {@link java.awt.image.BufferedImage BufferedImage}s
     */
    private void displayScreenshots(ArrayList<BufferedImage> screenShots) {
        if(screenShots.size() < 2) {
            // no need to select if you only have 1
            selectedScreen = 0;
            return;
        }


        JFrame f = new JFrame("Select a screen");
        int screenShotSize = screenShots.size();
        int dimensions = screenShotSize * 350;

        f.setLayout(new GridBagLayout());
        f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        int count = 0;
        for (BufferedImage image: screenShots){
            JButton temp = new JButton(new ImageIcon(resizeImage(image,300,200)));
            temp.setName(String.format("%d", count));
            temp.addActionListener(x -> handleMonitorSelection(x, f));
            f.add(temp);
            count++;
        }

        f.setSize(dimensions,300);
        centreWindow(f);
        f.setVisible(true);
    }

    /**
     * Helper method to handle the selection of a monitor.
     * @param event The {@link java.awt.event.ActionEvent ActionEvent} that fired to call this method.
     * @param window The {@link javax.swing.JFrame JFrame} to dispose.
     */
    private void handleMonitorSelection(ActionEvent event, JFrame window) {
        JButton source = (JButton) event.getSource();

        try {
            selectedScreen = Integer.parseInt(source.getName());
            StateController.displayMessage("Selected Screen [" + selectedScreen + "]", 2000);
        } catch(NumberFormatException nfe) {
            selectedScreen = -1;
            StateController.displayMessage("Failed to make screen selection from text [" +
                    source.getName() + "]" , 2000);
            LOGGER.warning("Failed to make screen selection from text [" + source.getName() + "] | "  +
                    ExceptionUtils.getStackTrace(nfe));
        }

        window.dispose();
    }

    /**
     * Helper method to center a window on the screen.
     * @param frame The {@link javax.swing.JFrame JFrame} to center.
     */
    public static void centreWindow(JFrame frame) {
        // TODO work with current selected screen here??
        Dimension dimension = Toolkit.getDefaultToolkit().getScreenSize();
        int x = (int) ((dimension.getWidth() - frame.getWidth()) / 2);
        int y = (int) ((dimension.getHeight() - frame.getHeight()) / 2);
        frame.setLocation(x, y);
    }

    /**
     * Helper method to resize images to a specific size.
     * @param originalImage The original {@link java.awt.image.BufferedImage BufferedImage} to resize.
     * @param targetWidth The target width in pixels.
     * @param targetHeight The target height in pixels.
     * @return A resized {@link java.awt.image.BufferedImage BufferedImage}
     */
    private BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        if(targetHeight == 0)
            targetHeight = 1;
        if(targetWidth == 0)
            targetWidth = 1;

        Image resultingImage = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_DEFAULT);
        BufferedImage outputImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        outputImage.getGraphics().drawImage(resultingImage, 0, 0, null);

        return outputImage;
    }

    /**
     * Helper method to display the key binding form.
     */
    private void showKeybindingForm(){
        JFrame keyBindingForm = new JFrame("KeyBindings");

        try{
            keyBindingForm.setLayout(new GridLayout(keyBindings.size() + 1,2,5,5));
            Enumeration<String> keyEnums = (Enumeration<String>) keyBindings.propertyNames();
            ArrayList<JTextField> propertyFields = new ArrayList<>();

            while (keyEnums.hasMoreElements()) {
                String key = keyEnums.nextElement();
                String value = keyBindings.getProperty(key);
                LOGGER.info(key + " : " + value);
                JLabel propertyName = new JLabel(key);
                propertyName.setHorizontalAlignment(JLabel.CENTER);
                JTextField propertyField = new JTextField(value);
                propertyField.setName(key);
                propertyFields.add(propertyField);
                keyBindingForm.add(propertyName);
                keyBindingForm.add(propertyField);
            }

            JButton confirmButton = new JButton("Confirm");
            confirmButton.addActionListener(new ActionListener(){
                public void actionPerformed(ActionEvent ae){
                    try (OutputStream output = new FileOutputStream("./settings/ImageRecognition.keybindings.properties")) {
                        for(JTextField jf: propertyFields){
                            //LOGGER.info("Key: " + jf.getName() + " Value: " + jf.getText());
                            keyBindings.setProperty(jf.getName(),jf.getText());
                        }
                        LOGGER.info("Settings confirmed.");
                        keyBindings.store(output,null);

                        // Update the application settings with the new values.
                        minimumMatchPercent =  trySetDefaultIntegers("minmatchpercent", 100);
                        defaultDimensionWidth = trySetDefaultIntegers("defaultwidgetwidth", 150);
                        defaultDimensionHeight = trySetDefaultIntegers("defaultwidgetheight", 150);

                        LOGGER.info("Minimum match = " + minimumMatchPercent + "% | Default widget size = [w="
                                + defaultDimensionWidth + ",h=" + defaultDimensionHeight +"]");


                        keyBindingForm.dispose();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });

            keyBindingForm.add(confirmButton);
            keyBindingForm.setMinimumSize(new Dimension(300,200));
            keyBindingForm.pack();
            centreWindow(keyBindingForm);
            keyBindingForm.setVisible(true);
        }
        catch(Exception e) {
            LOGGER.warning("Failed! | " + ExceptionUtils.getStackTrace(e));
        }
    }

    /**
     * Helper method to extract some repetitive try/catch code from parsing the {@link java.util.Properties Properties}
     * for the project while setting some default values.
     * @param key The {@link java.lang.String String} representation of the key value in the {@link #keyBindings} list.
     * @param defaultValue The int representation of the default value of the parameter.
     * @return The stored value in int format if it exists and can be parsed as an int, otherwise the default value.
     */
    private int trySetDefaultIntegers(String key, int defaultValue) {
        try {
            return Integer.parseInt(keyBindings.getProperty(key));
        } catch (NumberFormatException e) {
            LOGGER.warning("Failed to parse the key [" + key + "] due to NumberFormatException.");
            return defaultValue;
        }
    }

    /**
     * Helper method to load keybindings, ensure that all keybindings exist, and store them back to disk.
     */
    private void getOrCreateKeybindings() {
        String file = "./settings/ImageRecognition.keybindings.properties";
        try {
            keyBindings.load(new FileReader(file));
        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.warning("failed to load keybindings");
        }

        keyBindings.putIfAbsent("leftclick", "C");
        keyBindings.putIfAbsent("rightclick", "V");
        keyBindings.putIfAbsent("doubleclick", "B");
        keyBindings.putIfAbsent("type", "N");
        keyBindings.putIfAbsent("check", "M");
        keyBindings.putIfAbsent("performwidgets", "R");
        keyBindings.putIfAbsent("home", "H");
        keyBindings.putIfAbsent("previousstate", "Q");
        keyBindings.putIfAbsent("nextstate", "E");
        keyBindings.putIfAbsent("menuaction", "X");
        keyBindings.putIfAbsent("widgetfindretries", "5");
        keyBindings.putIfAbsent("minmatchpercent", "100");
        keyBindings.putIfAbsent("defaultwidgetwidth", "150");
        keyBindings.putIfAbsent("defaultwidgetheight", "150");
        keyBindings.putIfAbsent("forcerepair", "A");

        try {
            keyBindings.store(new FileOutputStream(file), "ImageRecognition Plugin Keybinds");
        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.warning("Could not save keybindings.");
        }
    }

    /**
     * Helper method to get the {@link java.lang.String String} representation of a {@link java.awt.event.KeyEvent KeyEvent}
     * @param keyChar The {@link java.awt.event.KeyEvent KeyEvent} to get the char for.
     * @return The {@link java.lang.String String} representation of the event if it is Alphabetic, is a Digit, or is Defined.
     * Otherwise an Empty String.
     */
    private static String getKeyText(char keyChar) {
        if(Character.isAlphabetic(keyChar) || Character.isDigit(keyChar)) //|| Character.isDefined(keyChar)
            return String.valueOf(keyChar);
        else
            return "";
    }

    /**
     * Custom KeyListener for class functionality.
     */
    private static class GlobalKeyboardListener implements NativeKeyListener {
        public void nativeKeyPressed(NativeKeyEvent e) {
            if (e.getKeyCode() == NativeKeyEvent.VC_CONTROL) {
                isControlClicked = true;
            }
            else if(e.getKeyCode() == NativeKeyEvent.VC_SHIFT) {
                isShiftClicked = true;
            }
        }

        public void nativeKeyReleased(NativeKeyEvent e) {
            if (e.getKeyCode() == NativeKeyEvent.VC_CONTROL) {
                isControlClicked = false;
            }
            else if(e.getKeyCode() == NativeKeyEvent.VC_SHIFT) {
                isShiftClicked = false;
            }
        }
        public void nativeKeyTyped(NativeKeyEvent e) {
            // Do nothing, has to exist to meet the interface implementation requirements.
        }
    }
}
