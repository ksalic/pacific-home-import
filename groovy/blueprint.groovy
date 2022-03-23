package org.hippoecm.frontend.plugins.cms.admin.updater

import org.apache.commons.lang.ArrayUtils
import org.apache.commons.lang.StringUtils
import org.hippoecm.repository.HippoStdPubWfNodeType
import org.hippoecm.repository.api.HippoNode
import org.hippoecm.repository.api.HippoNodeType
import org.hippoecm.repository.util.CopyHandler
import org.hippoecm.repository.util.JcrUtils
import org.hippoecm.repository.util.NodeInfo
import org.onehippo.repository.update.BaseNodeUpdateVisitor

import javax.jcr.*
import java.util.regex.Matcher
import java.util.regex.Pattern

class UpdaterTemplate extends BaseNodeUpdateVisitor {

  private static final String CONFIG_PATH = "/hst:brxsaas/hst:configurations/"
  private static final String NEW_PACIFIC_HOME_WEBSITE = "new-pacific-home-website";
  private static final String QUERY_TEMPLATE_DIR = "/hippo:configuration/hippo:queries/hippo:templates/new-subsite/hippostd:templates/";
  private static final String QUERY_TEMPLATE_NEW_PACIFIC_HOME_WEBSITE = QUERY_TEMPLATE_DIR + NEW_PACIFIC_HOME_WEBSITE;
  private static final String BLUEPRINT_NEW_PACIFIC_HOME_SITE_CONFIG = "/hst:brxsaas/hst:blueprints/new-pacific-home-website/hst:configuration";


  boolean doUpdate(Node node) {
    boolean contentUpdateComplete = false;
    boolean configUpdateComplete = false;
    if (node.isNodeType("hippostd:folder")) {

      String contentPath = node.getPath()
      String configPath = CONFIG_PATH + node.getName();

      Session session = node.getSession();

      if (session.itemExists(QUERY_TEMPLATE_NEW_PACIFIC_HOME_WEBSITE)) {
        session.removeItem(QUERY_TEMPLATE_NEW_PACIFIC_HOME_WEBSITE);
        log.debug("removed old " + QUERY_TEMPLATE_NEW_PACIFIC_HOME_WEBSITE);
      }

      if (session.itemExists(contentPath)) {

        Node contentNode = session.getNode(contentPath);
        Node parent = contentNode

        FolderCopyTask copyContentToBluePrintTask = new FolderCopyTask(session, null, parent, session.getNode(QUERY_TEMPLATE_DIR), NEW_PACIFIC_HOME_WEBSITE, NEW_PACIFIC_HOME_WEBSITE);
        copyContentToBluePrintTask.execute();
        contentUpdateComplete = true;
        log.debug("updated content " + QUERY_TEMPLATE_NEW_PACIFIC_HOME_WEBSITE);
      }

      if (contentUpdateComplete && session.itemExists(configPath)) {
        Node configNode = session.getNode(configPath);

        Node bluePrintConfigNode = session.getNode(BLUEPRINT_NEW_PACIFIC_HOME_SITE_CONFIG);
        String bluePrintConfigName = bluePrintConfigNode.getName();
        Node destinationParentNode = bluePrintConfigNode.getParent();
        bluePrintConfigNode.remove();
        JcrCopyUtils.copy(configNode, bluePrintConfigName, destinationParentNode);
        configUpdateComplete = true;
        log.debug("updated configuration " + BLUEPRINT_NEW_PACIFIC_HOME_SITE_CONFIG);

      }


    }
    return contentUpdateComplete && configUpdateComplete ? true : false;
  }

  boolean undoUpdate(Node node) {
    throw new UnsupportedOperationException('Updater does not implement undoUpdate method')
  }


  public class FolderCopyTask extends AbstractFolderCopyOrMoveTask {

    public FolderCopyTask(final Session session, final Locale locale, final Node sourceFolderNode,
                          final Node destParentFolderNode, final String destFolderNodeName, final String destFolderDisplayName) {
      super(session, locale, sourceFolderNode, destParentFolderNode, destFolderNodeName, destFolderDisplayName);
    }

    @Override
    protected void doExecute() throws RepositoryException {
      if (getSourceFolderNode().getParent().isSame(getDestParentFolderNode())) {
        if (StringUtils.equals(getSourceFolderNode().getName(), getDestFolderNodeName())) {
          throw new RuntimeException("Cannot copy to the same folder: " + getDestParentFolderNode().getPath() +" / " + getDestFolderNodeName());
        }
      }

      if (getSourceFolderNode().isSame(getDestParentFolderNode())) {
        throw new RuntimeException("Cannot copy to the folder itself: " + getDestFolderPath());
      }

      if (getSession().nodeExists(getDestFolderPath())) {
        throw new RuntimeException("Destination folder already exists: " + getDestFolderPath());
      }

      log.info("Copying nodes: from {} to {}.", getSourceFolderNode().getPath(), getDestFolderPath());

      if (getCopyHandler() != null) {
        JcrCopyUtils.copy(getSourceFolderNode(), getDestFolderNodeName(), getDestParentFolderNode(),
                getCopyHandler());
      } else {
        JcrCopyUtils.copy(getSourceFolderNode(), getDestFolderNodeName(), getDestParentFolderNode());
      }

      setDestFolderNode(JcrUtils.getNodeIfExists(getDestParentFolderNode(), getDestFolderNodeName()));

      updateFolderTranslations(getDestFolderNode(), getDestFolderDisplayName(), getLocale().getLanguage());
    }

    @Override
    protected void doAfterExecute() throws RepositoryException {
      resetHippoDocBaseLinks();
      takeOfflineHippoDocs();
      resetHippoDocumentTranslationIds();
    }

    /**
     * Search all the hippotranslation:translated nodes under {@code destFolderNode} including {@code destFolderNode}
     * and reset the hippotranslation:id property to a newly generate UUID.
     */
    protected void resetHippoDocumentTranslationIds() {
      String destFolderNodePath = null;

      try {
        destFolderNodePath = getDestFolderNode().getPath();

        final Map<String, String> uuidMappings = new HashMap<String, String>();

        resetHippoTranslatedNodeWithNewUuid(getDestFolderNode(), uuidMappings);

        JcrTraverseUtils.traverseNodes(getDestFolderNode(),
                new NodeTraverser() {
                  @Override
                  public boolean isAcceptable(Node node) throws RepositoryException {
                    return node.isNodeType("hippotranslation:translated");
                  }

                  @Override
                  public boolean isTraversable(Node node) throws RepositoryException {
                    return !node.isNodeType("hippostdpubwf:document");
                  }

                  @Override
                  public void accept(Node translatedNode) throws RepositoryException {
                    resetHippoTranslatedNodeWithNewUuid(translatedNode, uuidMappings);
                  }
                });
      } catch (RepositoryException e) {
        log.error("Failed to reset hippotranslation:id properties under {}.", destFolderNodePath, e);
      }
    }

    private void resetHippoTranslatedNodeWithNewUuid(final Node translatedNode, final Map<String, String> uuidMappings)
            throws RepositoryException {
      if (translatedNode != null && translatedNode.isNodeType("hippotranslation:translated")) {
        String translationUuid = JcrUtils.getStringProperty(translatedNode, "hippotranslation:id", null);

        if (UUIDUtils.isValidPattern(translationUuid)) {
          String newTranslationUuid;

          if (uuidMappings.containsKey(translationUuid)) {
            newTranslationUuid = uuidMappings.get(translationUuid);
          } else {
            newTranslationUuid = UUID.randomUUID().toString();
            uuidMappings.put(translationUuid, newTranslationUuid);
          }

          translatedNode.setProperty("hippotranslation:id", newTranslationUuid);
        }
      }
    }

    /**
     * Search all the link holder nodes having hippo:docbase property under destFolderNode
     * and reset the hippo:docbase properties to the copied nodes under destFolderNode
     * by comparing the relative paths with the corresponding nodes under the sourceFolderNode.
     */
    protected void resetHippoDocBaseLinks() {
      String destFolderNodePath = null;

      try {
        destFolderNodePath = getDestFolderNode().getPath();

        JcrTraverseUtils.traverseNodes(getDestFolderNode(),
                new NodeTraverser() {
                  private String sourceFolderBase = getSourceFolderNode().getPath() + "/";

                  @Override
                  public boolean isAcceptable(Node node) throws RepositoryException {
                    return node.isNodeType("hippo:mirror") && node.hasProperty("hippo:docbase");
                  }

                  @Override
                  public boolean isTraversable(Node node) throws RepositoryException {
                    return !node.isNodeType("hippo:mirror");
                  }

                  @Override
                  public void accept(Node destLinkHolderNode) throws RepositoryException {
                    String destLinkDocBase = JcrUtils.getStringProperty(destLinkHolderNode, "hippo:docbase", null);

                    if (StringUtils.isNotBlank(destLinkDocBase)) {
                      try {
                        Node sourceLinkedNode = getSession().getNodeByIdentifier(destLinkDocBase);

                        if (StringUtils.startsWith(sourceLinkedNode.getPath(), sourceFolderBase)) {
                          String sourceLinkedNodeRelPath = StringUtils.removeStart(sourceLinkedNode.getPath(),
                                  sourceFolderBase);
                          Node destLinkedNode = JcrUtils.getNodeIfExists(getDestFolderNode(), sourceLinkedNodeRelPath);

                          if (destLinkedNode != null) {
                            log.info("Updating the linked node at '{}'.", destLinkHolderNode.getPath());
                            destLinkHolderNode.setProperty("hippo:docbase", destLinkedNode.getIdentifier());
                          }
                        }
                      } catch (ItemNotFoundException ignore) {
                      }
                    }
                  }
                });
      } catch (RepositoryException e) {
        log.error("Failed to reset link Nodes under destination folder: {}.", destFolderNodePath, e);
      }
    }

    /**
     * Takes offline all the hippo documents under the {@code destFolderNode}.
     */
    protected void takeOfflineHippoDocs() {
      String destFolderNodePath = null;

      try {
        destFolderNodePath = getDestFolderNode().getPath();

        JcrTraverseUtils.traverseNodes(getDestFolderNode(),
                new NodeTraverser() {
                  @Override
                  public boolean isAcceptable(Node node) throws RepositoryException {
                    if (!node.isNodeType("hippostdpubwf:document")) {
                      return false;
                    }

                    return isLiveVariantNode(node) &&
                            StringUtils.equals("published", JcrUtils.getStringProperty(node, "hippostd:state", null));
                  }

                  @Override
                  public boolean isTraversable(Node node) throws RepositoryException {
                    return !node.isNodeType("hippostdpubwf:document");
                  }

                  private boolean isLiveVariantNode(final Node variantNode) throws RepositoryException {
                    if (variantNode.hasProperty("hippo:availability")) {
                      for (Value value : variantNode.getProperty("hippo:availability").getValues()) {
                        if (StringUtils.equals("live", value.getString())) {
                          return true;
                        }
                      }
                    }

                    return false;
                  }

                  @Override
                  public void accept(Node liveVariant) throws RepositoryException {
                    liveVariant.setProperty("hippo:availability", ArrayUtils.EMPTY_STRING_ARRAY);
                    liveVariant.setProperty("hippostd:stateSummary", "new");
                  }
                });
      } catch (RepositoryException e) {
        log.error("Failed to take offline link hippostd:publishableSummary nodes under {}.", destFolderNodePath, e);
      }
    }

  }


  public class JcrCopyUtils {

    private JcrCopyUtils() {
    }

    public static Node copy(final Node srcNode, final String destNodeName, final Node destParentNode)
            throws RepositoryException {
      return JcrUtils.copy(srcNode, destNodeName, destParentNode);
    }

    public static Node copy(final Node srcNode, final String destNodeName, final Node destParentNode,
                            final CopyHandler copyHandler) throws RepositoryException {

      if (copyHandler == null) {
        throw new IllegalArgumentException("copyHandler must not be null!");
      }

      if (JcrUtils.isVirtual(srcNode)) {
        return null;
      }

      if (destNodeName.indexOf('/') != -1) {
        throw new IllegalArgumentException(destNodeName + " is a path, not a name");
      }

      if (srcNode.isSame(destParentNode)) {
        throw new IllegalArgumentException("Destination parent node cannot be the same as source node");
      }

      if (isAncestor(srcNode, destParentNode)) {
        throw new IllegalArgumentException("Destination parent node cannot be descendant of source node");
      }

      final NodeInfo nodeInfo = new NodeInfo(srcNode);
      final NodeInfo newInfo = new NodeInfo(destNodeName, 0, nodeInfo.getNodeType(), nodeInfo.getMixinTypes());
      copyHandler.startNode(newInfo);
      final Node destNode = copyHandler.getCurrent();
      JcrUtils.copyToChain(srcNode, copyHandler);
      copyHandler.endNode();

      return destNode;
    }

    private static boolean isAncestor(final Node ancestor, final Node descendant) throws RepositoryException {
      try {
        Node node = descendant;

        while (!ancestor.isSame(node)) {
          node = node.getParent();
        }

        return true;
      } catch (ItemNotFoundException e) {
        return false;
      }
    }

  }

  public abstract class AbstractFolderCopyOrMoveTask extends AbstractFolderTask {

    private final Node destParentFolderNode;
    private final String destFolderNodeName;
    private final String destFolderDisplayName;
    private Node destFolderNode;

    private CopyHandler copyHandler;

    public AbstractFolderCopyOrMoveTask(final Session session, final Locale locale, final Node sourceFolderNode,
                                        final Node destParentFolderNode, final String destFolderNodeName, final String destFolderDisplayName) {
      super(session, locale, sourceFolderNode);

      this.destParentFolderNode = destParentFolderNode;
      this.destFolderNodeName = destFolderNodeName;
      this.destFolderDisplayName = destFolderDisplayName;
    }

    public Node getDestParentFolderNode() {
      return destParentFolderNode;
    }

    public String getDestFolderNodeName() {
      return destFolderNodeName;
    }

    public String getDestFolderDisplayName() {
      return destFolderDisplayName;
    }

    public Node getDestFolderNode() {
      return destFolderNode;
    }

    public void setDestFolderNode(final Node destFolderNode) {
      this.destFolderNode = destFolderNode;
    }

    public String getDestFolderPath() throws RepositoryException {
      return getDestParentFolderNode().getPath() + "/" + getDestFolderNodeName();
    }

    public CopyHandler getCopyHandler() {
      return copyHandler;
    }

    public void setCopyHandler(CopyHandler copyHandler) {
      this.copyHandler = copyHandler;
    }

    protected void recomputeHippoPaths(final Node folderNode) {
      try {
        JcrTraverseUtils.traverseNodes(getDestFolderNode(),
                new NodeTraverser() {
                  @Override
                  public boolean isAcceptable(Node node) throws RepositoryException {
                    if (!(node instanceof HippoNode)) {
                      return false;
                    }

                    if (node.isNodeType(HippoNodeType.NT_DERIVED)) {
                      return true;
                    }

                    if (node.isNodeType(HippoNodeType.NT_DOCUMENT) && node.hasProperty(HippoNodeType.HIPPO_PATHS)) {
                      return true;
                    }

                    return false;
                  }

                  @Override
                  public boolean isTraversable(Node node) throws RepositoryException {
                    return !node.isNodeType(HippoStdPubWfNodeType.HIPPOSTDPUBWF_DOCUMENT);
                  }

                  @Override
                  public void accept(Node node) throws RepositoryException {
                    ((HippoNode) node).recomputeDerivedData();
                  }
                });
      } catch (RepositoryException e) {
        log.error("Failed to touch hippo:paths properties.", e);
      }
    }

  }

  public class UUIDUtils {

    private static Pattern UUID_PATTERN = Pattern.compile("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");

    private UUIDUtils() {
    }

    public static boolean isValidPattern(final String input) {
      if (StringUtils.isEmpty(input)) {
        return false;
      }

      final Matcher m = UUID_PATTERN.matcher(input);
      return m.matches();
    }

  }


/**
 * Traversing node visitor abstraction.
 */
  public interface NodeTraverser {

    /**
     * Returns true if the {@code node} is acceptable.
     * @param node node
     * @return true if the {@code node} is acceptable
     * @throws RepositoryException if repository exception occurs
     */
    boolean isAcceptable(Node node) throws RepositoryException;

    /**
     * Returns true if the {@code node} can be traversed further to its descendants.
     * @param node node
     * @return true if the {@code node} can be traversed further to its descendants
     * @throws RepositoryException if repository exception occurs
     */
    boolean isTraversable(Node node) throws RepositoryException;

    /**
     * Accept the {@code node}.
     * @param node node
     * @throws RepositoryException if repository exception occurs
     */
    void accept(Node node) throws RepositoryException;

  }


  public class JcrTraverseUtils {

    private JcrTraverseUtils() {
    }

    public static void traverseNodes(final Node node, NodeTraverser nodeTraverser) throws RepositoryException {
      if (nodeTraverser == null) {
        throw new IllegalArgumentException("Provide a non-null nodeTraverser!");
      }

      if (nodeTraverser.isAcceptable(node)) {
        nodeTraverser.accept(node);
      }

      if (nodeTraverser.isTraversable(node) && node.hasNodes()) {
        Node childNode;

        for (NodeIterator nodeIt = node.getNodes(); nodeIt.hasNext();) {
          childNode = nodeIt.nextNode();

          if (childNode != null) {
            traverseNodes(childNode, nodeTraverser);
          }
        }
      }
    }

  }

  public abstract class AbstractFolderTask {

    private final Session session;
    private final Node sourceFolderNode;
    private final Locale locale;

    public AbstractFolderTask(final Session session, final Locale locale, final Node sourceFolderNode) {
      this.session = session;

      if (locale != null) {
        this.locale = locale;
      } else {
        this.locale = Locale.getDefault();
      }
      this.sourceFolderNode = sourceFolderNode;
    }

    public Session getSession() {
      return session;
    }

    public Locale getLocale() {
      return locale;
    }

    public Node getSourceFolderNode() {
      return sourceFolderNode;
    }

    public final void execute() throws RepositoryException {
      doBeforeExecute();
      doExecute();
      doAfterExecute();
    }

    protected void doBeforeExecute() throws RepositoryException {
    }

    abstract protected void doExecute() throws RepositoryException;

    protected void doAfterExecute() throws RepositoryException {
    }

    protected void updateFolderTranslations(final Node folderNode, final String displayName, String... langsToFind) {
      String folderPath = null;

      try {
        folderPath = folderNode.getPath();

        if (StringUtils.isNotBlank(displayName)) {
          if (!folderNode.isNodeType(HippoNodeType.NT_NAMED)) {
            folderNode.addMixin(HippoNodeType.NT_NAMED);
          }
          folderNode.setProperty(HippoNodeType.HIPPO_NAME, displayName);
        }
      } catch (RepositoryException e) {
        log.error("Failed to set hippo:translation node of folder at {} with value='{}'.",
                folderPath, displayName, e);
      }
    }

  }


}