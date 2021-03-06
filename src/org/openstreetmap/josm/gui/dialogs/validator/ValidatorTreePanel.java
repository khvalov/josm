// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.dialogs.validator;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.data.validation.util.MultipleNameVisitor;
import org.openstreetmap.josm.gui.preferences.validator.ValidatorPreference;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.tools.Destroyable;
import org.openstreetmap.josm.tools.ListenerList;

/**
 * A panel that displays the error tree. The selection manager
 * respects clicks into the selection list. Ctrl-click will remove entries from
 * the list while single click will make the clicked entry the only selection.
 *
 * @author frsantos
 */
public class ValidatorTreePanel extends JTree implements Destroyable {

    private static final class GroupTreeNode extends DefaultMutableTreeNode {

        GroupTreeNode(Object userObject) {
            super(userObject);
        }

        @Override
        public String toString() {
            return tr("{0} ({1})", super.toString(), getLeafCount());
        }
    }

    /**
     * The validation data.
     */
    protected DefaultTreeModel valTreeModel = new DefaultTreeModel(new DefaultMutableTreeNode());

    /** The list of errors shown in the tree */
    private transient List<TestError> errors = new ArrayList<>();

    /**
     * If {@link #filter} is not <code>null</code> only errors are displayed
     * that refer to one of the primitives in the filter.
     */
    private transient Set<? extends OsmPrimitive> filter;

    private final ListenerList<Runnable> invalidationListeners = ListenerList.create();

    /**
     * Constructor
     * @param errors The list of errors
     */
    public ValidatorTreePanel(List<TestError> errors) {
        ToolTipManager.sharedInstance().registerComponent(this);
        this.setModel(valTreeModel);
        this.setRootVisible(false);
        this.setShowsRootHandles(true);
        this.expandRow(0);
        this.setVisibleRowCount(8);
        this.setCellRenderer(new ValidatorTreeRenderer());
        this.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        setErrorList(errors);
        for (KeyListener keyListener : getKeyListeners()) {
            // Fix #3596 - Remove default keyListener to avoid conflicts with JOSM commands
            if ("javax.swing.plaf.basic.BasicTreeUI$Handler".equals(keyListener.getClass().getName())) {
                removeKeyListener(keyListener);
            }
        }
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        String res = null;
        TreePath path = getPathForLocation(e.getX(), e.getY());
        if (path != null) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Object nodeInfo = node.getUserObject();

            if (nodeInfo instanceof TestError) {
                TestError error = (TestError) nodeInfo;
                MultipleNameVisitor v = new MultipleNameVisitor();
                v.visit(error.getPrimitives());
                res = "<html>" + v.getText() + "<br>" + error.getMessage();
                String d = error.getDescription();
                if (d != null)
                    res += "<br>" + d;
                res += "</html>";
            } else {
                res = node.toString();
            }
        }
        return res;
    }

    /** Constructor */
    public ValidatorTreePanel() {
        this(null);
    }

    @Override
    public void setVisible(boolean v) {
        if (v) {
            buildTree();
        } else {
            valTreeModel.setRoot(new DefaultMutableTreeNode());
        }
        super.setVisible(v);
        invalidationListeners.fireEvent(Runnable::run);
    }

    /**
     * Builds the errors tree
     */
    public void buildTree() {
        final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();

        if (errors == null || errors.isEmpty()) {
            GuiHelper.runInEDTAndWait(() -> valTreeModel.setRoot(rootNode));
            return;
        }
        // Sort validation errors - #8517
        Collections.sort(errors);

        // Remember the currently expanded rows
        Set<Object> oldSelectedRows = new HashSet<>();
        Enumeration<TreePath> expanded = getExpandedDescendants(new TreePath(getRoot()));
        if (expanded != null) {
            while (expanded.hasMoreElements()) {
                TreePath path = expanded.nextElement();
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                Object userObject = node.getUserObject();
                if (userObject instanceof Severity) {
                    oldSelectedRows.add(userObject);
                } else if (userObject instanceof String) {
                    String msg = (String) userObject;
                    int index = msg.lastIndexOf(" (");
                    if (index > 0) {
                        msg = msg.substring(0, index);
                    }
                    oldSelectedRows.add(msg);
                }
            }
        }

        Predicate<TestError> filterToUse = e -> !e.isIgnored();
        if (!ValidatorPreference.PREF_OTHER.get()) {
            filterToUse = filterToUse.and(e -> e.getSeverity() != Severity.OTHER);
        }
        if (filter != null) {
            filterToUse = filterToUse.and(e -> e.getPrimitives().stream().anyMatch(filter::contains));
        }
        Map<Severity, Map<String, Map<String, List<TestError>>>> errorTreeDeep
            = errors.stream().filter(filterToUse).collect(
                    Collectors.groupingBy(TestError::getSeverity, () -> new EnumMap<>(Severity.class),
                            Collectors.groupingBy(e -> e.getDescription() == null ? "" : e.getDescription(),
                                    Collectors.groupingBy(TestError::getMessage))));

        List<TreePath> expandedPaths = new ArrayList<>();
        errorTreeDeep.forEach((s, severityErrorsDeep) -> {
            // Severity node
            DefaultMutableTreeNode severityNode = new GroupTreeNode(s);
            rootNode.add(severityNode);

            if (oldSelectedRows.contains(s)) {
                expandedPaths.add(new TreePath(new Object[] {rootNode, severityNode}));
            }

            Map<String, List<TestError>> severityErrors = severityErrorsDeep.get("");
            if (severityErrors != null) {
                for (Entry<String, List<TestError>> msgErrors : severityErrors.entrySet()) {
                    // Message node
                    List<TestError> errs = msgErrors.getValue();
                    String msg = tr("{0} ({1})", msgErrors.getKey(), errs.size());
                    DefaultMutableTreeNode messageNode = new DefaultMutableTreeNode(msg);
                    severityNode.add(messageNode);

                    if (oldSelectedRows.contains(msgErrors.getKey())) {
                        expandedPaths.add(new TreePath(new Object[] {rootNode, severityNode, messageNode}));
                    }

                    errs.stream().map(DefaultMutableTreeNode::new).forEach(messageNode::add);
                }
            }

            severityErrorsDeep.forEach((description, errorlist) -> {
                if (description.isEmpty()) {
                    return;
                }
                // Group node
                DefaultMutableTreeNode groupNode;
                if (errorlist.size() > 1) {
                    groupNode = new GroupTreeNode(description);
                    severityNode.add(groupNode);
                    if (oldSelectedRows.contains(description)) {
                        expandedPaths.add(new TreePath(new Object[] {rootNode, severityNode, groupNode}));
                    }
                } else {
                    groupNode = null;
                }

                errorlist.forEach((message, errs) -> {
                    // Message node
                    String msg;
                    if (groupNode != null) {
                        msg = tr("{0} ({1})", message, errs.size());
                    } else {
                        msg = tr("{0} - {1} ({2})", message, description, errs.size());
                    }
                    DefaultMutableTreeNode messageNode = new DefaultMutableTreeNode(msg);
                    if (groupNode != null) {
                        groupNode.add(messageNode);
                    } else {
                        severityNode.add(messageNode);
                    }

                    if (oldSelectedRows.contains(message)) {
                        if (groupNode != null) {
                            expandedPaths.add(new TreePath(new Object[] {rootNode, severityNode, groupNode, messageNode}));
                        } else {
                            expandedPaths.add(new TreePath(new Object[] {rootNode, severityNode, messageNode}));
                        }
                    }

                    errs.stream().map(DefaultMutableTreeNode::new).forEach(messageNode::add);
                });
            });
        });

        valTreeModel.setRoot(rootNode);
        for (TreePath path : expandedPaths) {
            this.expandPath(path);
        }

        invalidationListeners.fireEvent(Runnable::run);
    }

    /**
     * Add a new invalidation listener
     * @param listener The listener
     */
    public void addInvalidationListener(Runnable listener) {
        invalidationListeners.addListener(listener);
    }

    /**
     * Remove an invalidation listener
     * @param listener The listener
     * @since 10880
     */
    public void removeInvalidationListener(Runnable listener) {
        invalidationListeners.removeListener(listener);
    }

    /**
     * Sets the errors list used by a data layer
     * @param errors The error list that is used by a data layer
     */
    public final void setErrorList(List<TestError> errors) {
        this.errors = errors;
        if (isVisible()) {
            buildTree();
        }
    }

    /**
     * Clears the current error list and adds these errors to it
     * @param newerrors The validation errors
     */
    public void setErrors(List<TestError> newerrors) {
        if (errors == null)
            return;
        clearErrors();
        DataSet ds = Main.getLayerManager().getEditDataSet();
        for (TestError error : newerrors) {
            if (!error.isIgnored()) {
                errors.add(error);
                if (ds != null) {
                    ds.addDataSetListener(error);
                }
            }
        }
        if (isVisible()) {
            buildTree();
        }
    }

    /**
     * Returns the errors of the tree
     * @return the errors of the tree
     */
    public List<TestError> getErrors() {
        return errors != null ? errors : Collections.<TestError>emptyList();
    }

    /**
     * Selects all errors related to the specified {@code primitives}, i.e. where {@link TestError#getPrimitives()}
     * returns a primitive present in {@code primitives}.
     * @param primitives collection of primitives
     */
    public void selectRelatedErrors(final Collection<OsmPrimitive> primitives) {
        final Collection<TreePath> paths = new ArrayList<>();
        walkAndSelectRelatedErrors(new TreePath(getRoot()), new HashSet<>(primitives)::contains, paths);
        getSelectionModel().clearSelection();
        for (TreePath path : paths) {
            expandPath(path);
            getSelectionModel().addSelectionPath(path);
        }
    }

    private void walkAndSelectRelatedErrors(final TreePath p, final Predicate<OsmPrimitive> isRelevant, final Collection<TreePath> paths) {
        final int count = getModel().getChildCount(p.getLastPathComponent());
        for (int i = 0; i < count; i++) {
            final Object child = getModel().getChild(p.getLastPathComponent(), i);
            if (getModel().isLeaf(child) && child instanceof DefaultMutableTreeNode
                    && ((DefaultMutableTreeNode) child).getUserObject() instanceof TestError) {
                final TestError error = (TestError) ((DefaultMutableTreeNode) child).getUserObject();
                if (error.getPrimitives() != null) {
                    if (error.getPrimitives().stream().anyMatch(isRelevant)) {
                        paths.add(p.pathByAddingChild(child));
                    }
                }
            } else {
                walkAndSelectRelatedErrors(p.pathByAddingChild(child), isRelevant, paths);
            }
        }
    }

    /**
     * Returns the filter list
     * @return the list of primitives used for filtering
     */
    public Set<? extends OsmPrimitive> getFilter() {
        return filter;
    }

    /**
     * Set the filter list to a set of primitives
     * @param filter the list of primitives used for filtering
     */
    public void setFilter(Set<? extends OsmPrimitive> filter) {
        if (filter != null && filter.isEmpty()) {
            this.filter = null;
        } else {
            this.filter = filter;
        }
        if (isVisible()) {
            buildTree();
        }
    }

    /**
     * Updates the current errors list
     */
    public void resetErrors() {
        List<TestError> e = new ArrayList<>(errors);
        setErrors(e);
    }

    /**
     * Expands complete tree
     */
    @SuppressWarnings("unchecked")
    public void expandAll() {
        DefaultMutableTreeNode root = getRoot();

        int row = 0;
        Enumeration<TreeNode> children = root.breadthFirstEnumeration();
        while (children.hasMoreElements()) {
            children.nextElement();
            expandRow(row++);
        }
    }

    /**
     * Returns the root node model.
     * @return The root node model
     */
    public DefaultMutableTreeNode getRoot() {
        return (DefaultMutableTreeNode) valTreeModel.getRoot();
    }

    private void clearErrors() {
        if (errors != null) {
            DataSet ds = Main.getLayerManager().getEditDataSet();
            if (ds != null) {
                for (TestError e : errors) {
                    ds.removeDataSetListener(e);
                }
            }
            errors.clear();
        }
    }

    @Override
    public void destroy() {
        clearErrors();
    }
}
