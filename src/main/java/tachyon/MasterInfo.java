package tachyon;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.mortbay.log.Log;
import org.apache.log4j.Logger;

import tachyon.thrift.ClientDependencyInfo;
import tachyon.thrift.ClientFileInfo;
import tachyon.thrift.ClientRawTableInfo;
import tachyon.thrift.ClientWorkerInfo;
import tachyon.thrift.Command;
import tachyon.thrift.CommandType;
import tachyon.thrift.DependencyDoesNotExistException;
import tachyon.thrift.FileAlreadyExistException;
import tachyon.thrift.FileDoesNotExistException;
import tachyon.thrift.InvalidPathException;
import tachyon.thrift.NetAddress;
import tachyon.thrift.NoLocalWorkerException;
import tachyon.thrift.SuspectedFileSizeException;
import tachyon.thrift.TableColumnException;
import tachyon.thrift.TableDoesNotExistException;

/**
 * A global view of filesystem in master.
 * @author Haoyuan Li haoyuan@cs.berkeley.edu
 */
public class MasterInfo {
  public static final String COL = "COL_";

  private final Logger LOG = Logger.getLogger(Config.LOGGER_TYPE);

  private final InetSocketAddress MASTER_ADDRESS;
  private final long START_TIME_NS_PREFIX;
  private final long START_TIME_MS;

  private AtomicInteger mInodeCounter = new AtomicInteger(0);
  private AtomicInteger mDependencyCounter = new AtomicInteger(0);
  private AtomicInteger mRerunCounter = new AtomicInteger(0);
  private AtomicInteger mUserCounter = new AtomicInteger(0);
  private AtomicInteger mWorkerCounter = new AtomicInteger(0);

  // Root Inode's id must be 1.
  private InodeFolder mRoot;

  private Map<Integer, Inode> mInodes = new HashMap<Integer, Inode>();
  private Map<Integer, Dependency> mDependencies = new HashMap<Integer, Dependency>();

  // TODO add initialization part for master failover or restart.
  private Set<Integer> mUncheckpointedDependencies = new HashSet<Integer>();
  private Set<Integer> mPriorityDependencies = new HashSet<Integer>();
  private Set<Integer> mLostFiles = new HashSet<Integer>();
  private Set<Integer> mBeingRecomputedFiles = new HashSet<Integer>();
  private Set<Integer> mMustRecomputeDependencies = new HashSet<Integer>();

  private Map<Long, WorkerInfo> mWorkers = new HashMap<Long, WorkerInfo>();
  private Map<InetSocketAddress, Long> mWorkerAddressToId = new HashMap<InetSocketAddress, Long>();
  private BlockingQueue<WorkerInfo> mLostWorkers = new ArrayBlockingQueue<WorkerInfo>(32);

  // TODO Check the logic related to this two lists.
  private PrefixList mWhiteList;
  private PrefixList mPinList;
  private Set<Integer> mIdPinList;

  private MasterLogWriter mMasterLogWriter;

  private Thread mHeartbeatThread;

  /**
   * System periodical status check.
   * 
   * @author Haoyuan
   */
  public class MasterHeartbeatExecutor implements HeartbeatExecutor {
    public MasterHeartbeatExecutor() {
    }

    @Override
    public void heartbeat() {
      LOG.debug("Periodical system status checking...");

      Set<Long> lostWorkers = new HashSet<Long>();

      synchronized (mWorkers) {
        for (Entry<Long, WorkerInfo> worker: mWorkers.entrySet()) {
          if (CommonUtils.getCurrentMs() - worker.getValue().getLastUpdatedTimeMs() 
              > Config.WORKER_TIMEOUT_MS) {
            LOG.error("The worker " + worker.getValue() + " got timed out!");
            mLostWorkers.add(worker.getValue());
            lostWorkers.add(worker.getKey());
          }
        }
        for (long workerId: lostWorkers) {
          WorkerInfo workerInfo = mWorkers.get(workerId);
          mWorkerAddressToId.remove(workerInfo.getAddress());
          mWorkers.remove(workerId);
        }
      }

      boolean hadFailedWorker = false;

      while (mLostWorkers.size() != 0) {
        hadFailedWorker = true;
        WorkerInfo worker = mLostWorkers.poll();

        // TODO these two locks are not efficient. Since node failure is rare, this is fine for now.
        synchronized (mRoot) {
          synchronized (mDependencies) {
            for (int id: worker.getFiles()) {
              mLostFiles.add(id);
              InodeFile tFile = (InodeFile) mInodes.get(id);
              if (tFile != null) {
                tFile.removeLocation(worker.getId());
                if (!tFile.hasCheckpointed() && !tFile.isInMemory()) {
                  int depId = tFile.getDependencyId();
                  if (depId == -1) {
                    LOG.error("Permanent Data loss: " + tFile);
                  } else {
                    Dependency dep = mDependencies.get(depId);
                    dep.addLostFile(id);

                    if (!Config.MASTER_PROACTIVE_RECOVERY) {
                      mMustRecomputeDependencies.add(depId);
                    }
                  }
                } else {
                  LOG.info("File " + tFile + " only lost an in memory copy from worker " +
                      worker.getId());
                }
              } 
            }
          }
        }
      }

      if (hadFailedWorker) {
        LOG.warn("Restarting failed workers");
        try {
          java.lang.Runtime.getRuntime().exec(Config.TACHYON_HOME + 
              "/bin/restart-failed-workers.sh");
        } catch (IOException e) {
          LOG.error(e.getMessage());
        }
      }
    }
  }

  public class RecomputationScheduler implements Runnable {
    @Override
    public void run() {
      while (true) {
        boolean hasLostFiles = false;
        boolean launched = false;
        synchronized (mRoot) {
          synchronized (mDependencies) {
            if (!mMustRecomputeDependencies.isEmpty()) {
              List<Integer> recomputeList = new ArrayList<Integer>();
              Queue<Integer> checkQueue = new LinkedList<Integer>();

              checkQueue.addAll(mMustRecomputeDependencies);
              while (!checkQueue.isEmpty()) {
                int depId = checkQueue.poll();
                Dependency dep = mDependencies.get(depId);
                boolean canLaunch = true;
                for (int k = 0; k < dep.PARENT_FILES.size(); k ++) {
                  int fildId = dep.PARENT_FILES.get(k);
                  if (mLostFiles.contains(fildId)) {
                    canLaunch = false;
                    InodeFile iFile = (InodeFile) mInodes.get(fildId);
                    if (!mBeingRecomputedFiles.contains(fildId)) {
                      int tDepId = iFile.getDependencyId();
                      if (tDepId != -1 && !mMustRecomputeDependencies.contains(tDepId)) {
                        mMustRecomputeDependencies.add(tDepId);
                        checkQueue.add(tDepId);
                      }
                    }
                  }
                }
                if (canLaunch) {
                  recomputeList.add(depId);
                }
              }
              hasLostFiles = !mMustRecomputeDependencies.isEmpty();
              launched = (recomputeList.size() > 0);

              for (int k = 0; k < recomputeList.size(); k ++) {
                mMustRecomputeDependencies.remove(recomputeList.get(k));
                Dependency dep = mDependencies.get(recomputeList.get(k));
                mBeingRecomputedFiles.addAll(dep.getLostFiles());
                String cmd = dep.getCommand();
                cmd += " &> " + Config.TACHYON_HOME + "/logs/rerun " +
                    mRerunCounter.incrementAndGet();
                try {
                  LOG.info("Exec " + cmd);
                  java.lang.Runtime.getRuntime().exec(cmd);
                } catch (IOException e) {
                  LOG.error(e.getMessage());
                }
              }
            }
          }
        }

        if (!launched && hasLostFiles) {
          LOG.info("HasLostFiles, but no job can be launched.");
          CommonUtils.sleep(LOG, 1000);
        }
      }
    }
  }

  public MasterInfo(InetSocketAddress address) {
    mRoot = new InodeFolder("", mInodeCounter.incrementAndGet(), -1);
    mInodes.put(mRoot.getId(), mRoot);

    MASTER_ADDRESS = address;
    START_TIME_MS = System.currentTimeMillis();
    // TODO This name need to be changed.
    START_TIME_NS_PREFIX = START_TIME_MS - (START_TIME_MS % 1000000);

    mWhiteList = new PrefixList(Config.WHITELIST);
    mPinList = new PrefixList(Config.PINLIST);
    mIdPinList = Collections.synchronizedSet(new HashSet<Integer>());

    // TODO Fault recovery: need user counter info;
    recoveryFromLog();
    writeCheckpoint();

    mMasterLogWriter = new MasterLogWriter(Config.MASTER_LOG_FILE);

    mHeartbeatThread = new Thread(new HeartbeatThread(
        new MasterHeartbeatExecutor(), Config.MASTER_HEARTBEAT_INTERVAL_MS));
    mHeartbeatThread.start();
  }

  public boolean addCheckpoint(long workerId, int fileId, long fileSizeBytes,
      String checkpointPath) throws FileDoesNotExistException, SuspectedFileSizeException {
    LOG.info("addCheckpoint" + CommonUtils.parametersToString(workerId, fileId, fileSizeBytes,
        checkpointPath));

    if (workerId != -1) {
      WorkerInfo tWorkerInfo = getWorkerInfo(workerId);
      tWorkerInfo.updateLastUpdatedTimeMs();
    }

    synchronized (mRoot) {
      Inode inode = mInodes.get(fileId);

      if (inode == null) {
        throw new FileDoesNotExistException("File " + fileId + " does not exist.");
      }
      if (inode.isDirectory()) {
        throw new FileDoesNotExistException("File " + fileId + " is a folder.");
      }

      InodeFile tFile = (InodeFile) inode;
      boolean needLog = false;

      if (tFile.isReady()) {
        if (tFile.getLength() != fileSizeBytes) {
          throw new SuspectedFileSizeException(fileId + ". Original Size: " +
              tFile.getLength() + ". New Size: " + fileSizeBytes);
        }
      } else {
        tFile.setLength(fileSizeBytes);
        needLog = true;
      }

      if (!tFile.hasCheckpointed()) {
        tFile.setCheckpointPath(checkpointPath);
        needLog = true;
        synchronized (mDependencies) {
          int depId = tFile.getDependencyId();
          if (depId != -1) {
            Dependency dep = mDependencies.get(depId);
            dep.addChildrenDependency(tFile.getId());
            if (dep.hasCheckpointed()) {
              mUncheckpointedDependencies.remove(dep.ID);
              mPriorityDependencies.remove(dep.ID);
            }
          }
        }
      }

      addFile(fileId, tFile.getDependencyId());

      if (needLog) {
        mMasterLogWriter.appendAndFlush(tFile);
      }
      return true;
    }
  }

  private void addFile(int fileId, int dependencyId) {
    synchronized (mDependencies) {
      if (mLostFiles.contains(fileId)) {
        mLostFiles.remove(fileId);
      }
      if (mBeingRecomputedFiles.contains(fileId)) {
        mBeingRecomputedFiles.remove(fileId);
      }
    }
  }

  /**
   * 
   * @param workerId
   * @param workerUsedBytes
   * @param fileId
   * @param fileSizeBytes
   * @return the dependency id of the file if it has not been checkpointed. -1 means the file 
   * either does not have dependency or has already been checkpointed.
   * @throws FileDoesNotExistException
   * @throws SuspectedFileSizeException
   */
  public int cachedFile(long workerId, long workerUsedBytes, int fileId,
      long fileSizeBytes) throws FileDoesNotExistException, SuspectedFileSizeException {
    LOG.info("cachedFile" + CommonUtils.parametersToString(workerId, workerUsedBytes, fileId, 
        fileSizeBytes));

    WorkerInfo tWorkerInfo = getWorkerInfo(workerId);
    tWorkerInfo.updateFile(true, fileId);
    tWorkerInfo.updateUsedBytes(workerUsedBytes);
    tWorkerInfo.updateLastUpdatedTimeMs();

    synchronized (mRoot) {
      Inode inode = mInodes.get(fileId);

      if (inode == null) {
        throw new FileDoesNotExistException("File " + fileId + " does not exist.");
      }
      if (inode.isDirectory()) {
        throw new FileDoesNotExistException("File " + fileId + " is a folder.");
      }

      InodeFile tFile = (InodeFile) inode;
      boolean needLog = false;

      if (tFile.isReady()) {
        if (tFile.getLength() != fileSizeBytes) {
          throw new SuspectedFileSizeException(fileId + ". Original Size: " +
              tFile.getLength() + ". New Size: " + fileSizeBytes);
        }
      } else {
        tFile.setLength(fileSizeBytes);
        needLog = true;
      }
      if (needLog) {
        mMasterLogWriter.appendAndFlush(tFile);
      }
      InetSocketAddress address = tWorkerInfo.ADDRESS;
      tFile.addLocation(workerId, new NetAddress(address.getHostName(), address.getPort()));

      addFile(fileId, tFile.getDependencyId());

      if (tFile.hasCheckpointed()) {
        return -1;
      } else {
        return tFile.getDependencyId();
      }
    }
  }

  public int createDependency(List<String> parents, List<String> children,
      String commandPrefix, List<ByteBuffer> data, String comment,
      String framework, String frameworkVersion, DependencyType dependencyType)
          throws InvalidPathException, FileDoesNotExistException {
    Dependency dep = null;
    synchronized (mRoot) {
      LOG.info("ParentList: " + CommonUtils.listToString(parents));
      List<Integer> parentsIdList = getFilesIds(parents);
      List<Integer> childrenIdList = getFilesIds(children);

      Set<Integer> parentDependencyIds = new HashSet<Integer>();
      for (int k = 0; k < parentsIdList.size(); k ++) {
        int parentId = parentsIdList.get(k);
        Inode inode = mInodes.get(parentId);
        if (inode.isFile()) {
          LOG.info("PARENT DEPENDENCY ID IS " + ((InodeFile) inode).getDependencyId() + " " +
              ((InodeFile) inode));
          parentDependencyIds.add(((InodeFile) inode).getDependencyId());
        } else {
          throw new InvalidPathException("Parent " + parentId + " is not a file.");
        }
      }

      dep = new Dependency(mDependencyCounter.incrementAndGet(), parentsIdList,
          childrenIdList, commandPrefix, data, comment, framework, frameworkVersion,
          dependencyType, parentDependencyIds);

      List<Inode> childrenInodeList = new ArrayList<Inode>();
      for (int k = 0; k < childrenIdList.size(); k ++) {
        InodeFile inode = (InodeFile) mInodes.get(childrenIdList.get(k));
        inode.setDependencyId(dep.ID);
        childrenInodeList.add(inode);
        if (inode.hasCheckpointed()) {
          dep.childCheckpointed(inode.getId());
        }
      }
      mMasterLogWriter.appendAndFlush(childrenInodeList);
    }

    synchronized (mDependencies) {
      mDependencies.put(dep.ID, dep);
      mMasterLogWriter.appendAndFlush(dep);
      if (!dep.hasCheckpointed()) {
        mUncheckpointedDependencies.add(dep.ID);
      }
      for (int parentDependencyId: dep.PARENT_DEPENDENCIES) {
        mDependencies.get(parentDependencyId).addChildrenDependency(dep.ID);
      }
    }

    LOG.info("Dependency created: " + dep);

    return dep.ID;
  }

  public int createFile(String path, boolean directory)
      throws FileAlreadyExistException, InvalidPathException {
    return createFile(true, path, directory, -1, null);
  }

  public int createFile(boolean recursive, String path, boolean directory, int columns,
      ByteBuffer metadata) throws FileAlreadyExistException, InvalidPathException {
    LOG.debug("createFile" + CommonUtils.parametersToString(path));

    String[] pathNames = getPathNames(path);

    synchronized (mRoot) {
      Inode inode = getInode(pathNames);
      if (inode != null) {
        Log.info("FileAlreadyExistException: File " + path + " already exist.");
        throw new FileAlreadyExistException("File " + path + " already exist.");
      }

      String name = pathNames[pathNames.length - 1];
      String folderPath = null;
      if (path.length() - name.length() == 1) {
        folderPath = path.substring(0, path.length() - name.length()); 
      } else {
        folderPath = path.substring(0, path.length() - name.length() - 1);
      }
      inode = getInode(folderPath);
      if (inode == null) {
        int succeed = 0;
        if (recursive) {
          succeed = createFile(true, folderPath, true, -1, null);
        }
        if (!recursive || succeed <= 0) {
          Log.info("InvalidPathException: File " + path + " creation failed. Folder "
              + folderPath + " does not exist.");
          throw new InvalidPathException("InvalidPathException: File " + path + " creation " +
              "failed. Folder " + folderPath + " does not exist.");
        } else {
          inode = mInodes.get(succeed);
        }
      } else if (inode.isFile()) {
        Log.info("InvalidPathException: File " + path + " creation failed. "
            + folderPath + " is a file.");
        throw new InvalidPathException("File " + path + " creation failed. "
            + folderPath + " is a file");
      }

      Inode ret = null;

      if (directory) {
        if (columns != -1) {
          ret = new InodeRawTable(name, mInodeCounter.incrementAndGet(), inode.getId(),
              columns, metadata);
        } else {
          ret = new InodeFolder(name, mInodeCounter.incrementAndGet(), inode.getId());
        }
      } else {
        ret = new InodeFile(name, mInodeCounter.incrementAndGet(), inode.getId());
        String curPath = getPath(ret);
        if (mPinList.inList(curPath)) {
          synchronized (mIdPinList) {
            mIdPinList.add(ret.getId());
            ((InodeFile) ret).setPin(true);
          }
        }
        if (mWhiteList.inList(curPath)) {
          ((InodeFile) ret).setCache(true);
        }
      }

      mInodes.put(ret.getId(), ret);
      ((InodeFolder) inode).addChild(ret.getId());

      // TODO this two appends should be atomic;
      mMasterLogWriter.appendAndFlush(inode);
      mMasterLogWriter.appendAndFlush(ret);

      LOG.debug("createFile: File Created: " + ret + " parent: " + inode);
      return ret.getId();
    }
  }

  public int createRawTable(String path, int columns, ByteBuffer metadata)
      throws FileAlreadyExistException, InvalidPathException, TableColumnException {
    LOG.info("createRawTable" + CommonUtils.parametersToString(path, columns));

    if (columns <= 0 || columns >= Config.MAX_COLUMNS) {
      throw new TableColumnException("Column " + columns + " should between 0 to " + 
          Config.MAX_COLUMNS);
    }

    int id = createFile(true, path, true, columns, metadata);

    for (int k = 0; k < columns; k ++) {
      createFile(path + Config.SEPARATOR + COL + k, true);
    }

    return id;
  }

  public void delete(int id) {
    LOG.info("delete(" + id + ")");
    // Only remove meta data from master. The data in workers will be evicted since no further
    // application can read them. (Based on LRU) TODO May change it to be active from V0.2. 
    synchronized (mRoot) {
      Inode inode = mInodes.get(id);

      if (inode == null) {
        return;
      }

      if (inode.isDirectory()) {
        List<Integer> childrenIds = ((InodeFolder) inode).getChildrenIds();

        for (int childId : childrenIds) {
          delete(childId);
        }
      }

      InodeFolder parent = (InodeFolder) mInodes.get(inode.getParentId());
      parent.removeChild(inode.getId());
      mInodes.remove(inode.getId());
      if (inode.isFile() && ((InodeFile) inode).isPin()) {
        synchronized (mIdPinList) {
          mIdPinList.remove(inode.getId());
        }
      }
      inode.reverseId();

      // TODO this following append should be atomic
      mMasterLogWriter.appendAndFlush(inode);
      mMasterLogWriter.appendAndFlush(parent);
    }
  }

  public void delete(String path) throws InvalidPathException, FileDoesNotExistException {
    LOG.info("delete(" + path + ")");
    synchronized (mRoot) {
      Inode inode = getInode(path);
      if (inode == null) {
        throw new FileDoesNotExistException(path);
      }
      delete(inode.getId());
    }
  }

  public long getCapacityBytes() {
    long ret = 0;
    synchronized (mWorkers) {
      for (WorkerInfo worker : mWorkers.values()) {
        ret += worker.getCapacityBytes();
      }
    }
    return ret;
  }

  public ClientDependencyInfo getClientDependencyInfo(int dependencyId) 
      throws DependencyDoesNotExistException {
    Dependency dep = null;
    synchronized (mDependencies) {
      dep = mDependencies.get(dependencyId);
      if (dep == null) {
        throw new DependencyDoesNotExistException("No dependency with id " + dependencyId);
      }
    }
    return dep.generateClientDependencyInfo();
  }

  public ClientFileInfo getClientFileInfo(int id) throws FileDoesNotExistException {
    synchronized (mRoot) {
      Inode inode = mInodes.get(id);
      if (inode == null) {
        throw new FileDoesNotExistException("FileId " + id + " does not exist.");
      }
      ClientFileInfo ret = new ClientFileInfo();
      ret.id = inode.getId();
      ret.name = inode.getName();
      ret.path = getPath(inode);
      ret.checkpointPath = "";
      ret.sizeBytes = 0;
      ret.creationTimeMs = inode.getCreationTimeMs();
      ret.inMemory = false;
      ret.ready = true;
      ret.folder = inode.isDirectory();
      ret.needPin = false;
      ret.needCache = false;
      ret.dependencyId = -1;

      if (inode.isFile()) {
        InodeFile tInode = (InodeFile) inode;
        ret.sizeBytes = tInode.getLength();
        ret.inMemory = tInode.isInMemory();
        ret.ready = tInode.isReady();
        ret.checkpointPath = tInode.getCheckpointPath();
        ret.needPin = tInode.isPin();
        ret.needCache = tInode.isCache();
        ret.dependencyId = tInode.getDependencyId();
      }

      LOG.debug("getClientFileInfo(" + id + "): "  + ret);
      return ret;
    }
  }

  public ClientFileInfo getClientFileInfo(String path)
      throws FileDoesNotExistException, InvalidPathException {
    LOG.info("getClientFileInfo(" + path + ")");
    synchronized (mRoot) {
      Inode inode = getInode(path);
      if (inode == null) {
        throw new FileDoesNotExistException(path);
      }
      return getClientFileInfo(inode.getId());
    }
  }

  public ClientRawTableInfo getClientRawTableInfo(int id) throws TableDoesNotExistException {
    LOG.info("getClientRawTableInfo(" + id + ")");
    synchronized (mRoot) {
      Inode inode = mInodes.get(id);
      if (inode == null || inode.isFile() || !((InodeFolder) inode).isRawTable()) {
        throw new TableDoesNotExistException("Table " + id + " does not exist.");
      }
      ClientRawTableInfo ret = new ClientRawTableInfo();
      ret.id = inode.getId();
      ret.name = inode.getName();
      ret.path = getPath(inode);
      ret.columns = ((InodeRawTable) inode).getColumns();
      ret.metadata = ((InodeRawTable) inode).getMetadata();
      return ret;
    }
  }

  public ClientRawTableInfo getClientRawTableInfo(String path)
      throws TableDoesNotExistException, InvalidPathException {
    LOG.info("getClientRawTableInfo(" + path + ")");
    synchronized (mRoot) {
      Inode inode = getInode(path);
      if (inode == null) {
        throw new TableDoesNotExistException(path);
      }
      return getClientRawTableInfo(inode.getId());
    }
  }

  public List<ClientFileInfo> getFilesInfo(String path) 
      throws FileDoesNotExistException, InvalidPathException {
    List<ClientFileInfo> ret = new ArrayList<ClientFileInfo>();

    Inode inode = getInode(path);

    if (inode == null) {
      throw new FileDoesNotExistException(path);
    }

    if (inode.isDirectory()) {
      List<Integer> childernIds = ((InodeFolder) inode).getChildrenIds();

      if (!path.endsWith("/")) {
        path += "/";
      }
      synchronized (mRoot) {
        for (int k : childernIds) {
          ret.add(getClientFileInfo(k));
        }
      }
    } else {
      ret.add(getClientFileInfo(inode.getId()));
    }

    return ret;
  }

  public ClientFileInfo getFileInfo(String path)
      throws FileDoesNotExistException, InvalidPathException {
    Inode inode = getInode(path);
    if (inode == null) {
      throw new FileDoesNotExistException(path);
    }
    return getClientFileInfo(inode.getId());
  }

  public List<NetAddress> getFileLocations(int fileId) throws FileDoesNotExistException {
    synchronized (mRoot) {
      Inode inode = mInodes.get(fileId);
      if (inode == null || inode.isDirectory()) {
        throw new FileDoesNotExistException("FileId " + fileId + " does not exist.");
      }
      LOG.debug("getFileLocations: " + fileId + ((InodeFile) inode).getLocations());
      return ((InodeFile) inode).getLocations();
    }
  }

  public List<NetAddress> getFileLocations(String path) 
      throws FileDoesNotExistException, InvalidPathException {
    LOG.info("getFileLocations: " + path);
    synchronized (mRoot) {
      Inode inode = getInode(path);
      if (inode == null) {
        throw new FileDoesNotExistException(path);
      }
      return getFileLocations(inode.getId());
    }
  }

  public int getFileId(String filePath) throws InvalidPathException {
    LOG.debug("getFileId(" + filePath + ")");
    Inode inode = getInode(filePath);
    int ret = -1;
    if (inode != null) {
      ret = inode.getId();
    }
    LOG.debug("getFileId(" + filePath + "): " + ret);
    return ret;
  }

  public List<Integer> getFilesIds(List<String> pathList)
      throws InvalidPathException, FileDoesNotExistException {
    List<Integer> ret = new ArrayList<Integer>(pathList.size());
    for (int k = 0; k < pathList.size(); k ++) {
      ret.addAll(listFiles(pathList.get(k), true));
    }
    return ret;
  }

  private Inode getInode(String path) throws InvalidPathException {
    return getInode(getPathNames(path));
  }

  private Inode getInode(String[] pathNames) throws InvalidPathException {
    if (pathNames == null || pathNames.length == 0) {
      return null;
    }
    if (pathNames.length == 1) {
      if (pathNames[0].equals("")) {
        return mRoot;
      } else {
        LOG.info("InvalidPathException: File name starts with " + pathNames[0]);
        throw new InvalidPathException("File name starts with " + pathNames[0]);
      }
    }

    Inode cur = mRoot;

    synchronized (mRoot) {
      for (int k = 1; k < pathNames.length && cur != null; k ++) {
        String name = pathNames[k];
        if (cur.isFile()) {
          return null;
        }
        cur = ((InodeFolder) cur).getChild(name, mInodes);
      }

      return cur;
    }
  }

  /**
   * Get absolute paths of all in memory files.
   *
   * @return absolute paths of all in memory files.
   */
  public List<String> getInMemoryFiles() {
    List<String> ret = new ArrayList<String>();
    LOG.info("getInMemoryFiles()");
    Queue<Pair<InodeFolder, String>> nodesQueue = new LinkedList<Pair<InodeFolder, String>>();
    synchronized (mRoot) {
      nodesQueue.add(new Pair<InodeFolder, String>(mRoot, ""));
      while (!nodesQueue.isEmpty()) {
        Pair<InodeFolder, String> tPair = nodesQueue.poll();
        InodeFolder tFolder = tPair.getFirst();
        String curPath = tPair.getSecond();

        List<Integer> childrenIds = tFolder.getChildrenIds();
        for (int id : childrenIds) {
          Inode tInode = mInodes.get(id);
          String newPath = curPath + Config.SEPARATOR + tInode.getName();
          if (tInode.isDirectory()) {
            nodesQueue.add(new Pair<InodeFolder, String>((InodeFolder) tInode, newPath));
          } else if (((InodeFile) tInode).isInMemory()) {
            ret.add(newPath);
          }
        }
      }
    }
    return ret;
  }

  public InetSocketAddress getMasterAddress() {
    return MASTER_ADDRESS;
  }

  private static String getName(String path) throws InvalidPathException {
    String[] pathNames = getPathNames(path);
    return pathNames[pathNames.length - 1];
  }

  public long getNewUserId() {
    return mUserCounter.incrementAndGet();
  }

  public int getNumberOfFiles(String path) throws InvalidPathException, FileDoesNotExistException {
    Inode inode = getInode(path);
    if (inode == null) {
      throw new FileDoesNotExistException(path);
    }
    if (inode.isFile()) {
      return 1;
    }
    return ((InodeFolder) inode).getNumberOfChildren();
  }

  private String getPath(Inode inode) {
    synchronized (mRoot) {
      if (inode.getId() == 1) {
        return "/";
      }
      if (inode.getParentId() == 1) {
        return Config.SEPARATOR + inode.getName();
      }
      return getPath(mInodes.get(inode.getParentId())) + Config.SEPARATOR + inode.getName();
    }
  }

  private static String[] getPathNames(String path) throws InvalidPathException {
    CommonUtils.validatePath(path);
    if (path.length() == 1 && path.equals(Config.SEPARATOR)) {
      String[] ret = new String[1];
      ret[0] = "";
      return ret;
    }
    return path.split(Config.SEPARATOR);
  }

  public List<String> getPinList() {
    return mPinList.getList();
  }

  public List<Integer> getPinIdList() {
    synchronized (mIdPinList) {
      List<Integer> ret = new ArrayList<Integer>();
      for (int id : mIdPinList) {
        ret.add(id);
      }
      return ret;
    }
  }

  public List<Integer> getPriorityDependencyList() {
    synchronized (mDependencies) {
      int earliestDepId = -1;
      if (mPriorityDependencies.isEmpty()) {
        long earliest = Long.MAX_VALUE;
        for (int depId: mUncheckpointedDependencies) {
          Dependency dep = mDependencies.get(depId); 
          if (!dep.hasChildrenDependency()) {
            mPriorityDependencies.add(dep.ID);
          }
          if (dep.CREATION_TIME_MS < earliest) {
            earliest = dep.CREATION_TIME_MS;
            earliestDepId = dep.ID;
          }
        }
      }

      if (mPriorityDependencies.isEmpty() && earliestDepId != -1) {
        mPriorityDependencies.add(earliestDepId);
      }

      List<Integer> ret = new ArrayList<Integer>(mPriorityDependencies.size());
      ret.addAll(mPriorityDependencies);
      return ret;
    }
  }

  public int getRawTableId(String path) throws InvalidPathException {
    Inode inode = getInode(path);
    if (inode == null || inode.isFile() || !((InodeFolder) inode).isRawTable()) {
      return -1;
    }
    return inode.getId();
  }

  public long getStarttimeMs() {
    return START_TIME_MS;
  }

  public long getUsedBytes() {
    long ret = 0;
    synchronized (mWorkers) {
      for (WorkerInfo worker : mWorkers.values()) {
        ret += worker.getUsedBytes();
      }
    }
    return ret;
  }

  public NetAddress getWorker(boolean random, String host) throws NoLocalWorkerException {
    synchronized (mWorkers) {
      if (random) {
        int index = new Random(mWorkerAddressToId.size()).nextInt(mWorkerAddressToId.size());
        for (InetSocketAddress address: mWorkerAddressToId.keySet()) {
          if (index == 0) {
            LOG.debug("getRandomWorker: " + address);
            return new NetAddress(address.getHostName(), address.getPort());
          }
          index --;
        }
        for (InetSocketAddress address: mWorkerAddressToId.keySet()) {
          LOG.debug("getRandomWorker: " + address);
          return new NetAddress(address.getHostName(), address.getPort());
        }
      } else {
        for (InetSocketAddress address: mWorkerAddressToId.keySet()) {
          if (address.getHostName().equals(host)) {
            LOG.debug("getLocalWorker: " + address);
            return new NetAddress(address.getHostName(), address.getPort());
          }
        }
      }
    }
    LOG.info("getLocalWorker: no local worker on " + host);
    throw new NoLocalWorkerException("getLocalWorker: no local worker on " + host);
  }

  public int getWorkerCount() {
    synchronized (mWorkers) {
      return mWorkers.size();
    }
  }

  private WorkerInfo getWorkerInfo(long workerId) {
    WorkerInfo ret = null;
    synchronized (mWorkers) {
      ret = mWorkers.get(workerId);

      if (ret == null) {
        LOG.error("No worker: " + workerId);
      }
    }
    return ret;
  }

  public List<ClientWorkerInfo> getWorkersInfo() {
    List<ClientWorkerInfo> ret = new ArrayList<ClientWorkerInfo>();

    synchronized (mWorkers) {
      for (WorkerInfo worker : mWorkers.values()) {
        ret.add(worker.generateClientWorkerInfo());
      }
    }

    return ret;
  }

  public List<String> getWhiteList() {
    return mWhiteList.getList();
  }

  public List<Integer> listFiles(String path, boolean recursive) 
      throws InvalidPathException, FileDoesNotExistException {
    List<Integer> ret = new ArrayList<Integer>(); 
    synchronized (mRoot) {
      Inode inode = getInode(path);
      if (inode == null) {
        throw new FileDoesNotExistException(path);
      }

      if (inode.isFile()) {
        ret.add(inode.getId());
      } else if (recursive) {
        Queue<Integer> queue = new LinkedList<Integer>();
        queue.addAll(((InodeFolder) inode).getChildrenIds());

        while (!queue.isEmpty()) {
          int id = queue.poll();
          inode = mInodes.get(id);

          if (inode.isDirectory()) {
            queue.addAll(((InodeFolder) inode).getChildrenIds());
          } else {
            ret.add(id);
          }
        }
      }
    }

    return ret;
  }

  public List<String> ls(String path, boolean recursive) 
      throws InvalidPathException, FileDoesNotExistException {
    List<String> ret = new ArrayList<String>();

    Inode inode = getInode(path);

    if (inode == null) {
      throw new FileDoesNotExistException(path);
    }

    if (inode.isFile()) {
      ret.add(path);
    } else if (recursive) {
      List<Integer> childernIds = ((InodeFolder) inode).getChildrenIds();

      if (!path.endsWith("/")) {
        path += "/";
      }
      synchronized (mRoot) {
        for (int k : childernIds) {
          inode = mInodes.get(k);
          if (inode != null) {
            ret.add(path + inode.getName());
          }
        }
      }
    }

    return ret;
  }

  private void recoveryFromFile(String fileName, String msg) {
    MasterLogReader reader;

    File file = new File(fileName);
    if (!file.exists()) {
      LOG.info(msg + fileName + " does not exist.");
    } else {
      LOG.info("Reading " + msg + fileName);
      reader = new MasterLogReader(fileName);
      while (reader.hasNext()) {
        Pair<LogType, Object> pair = reader.getNextPair();
        switch (pair.getFirst()) {
          case CheckpointInfo: {
            CheckpointInfo checkpointInfo = (CheckpointInfo) pair.getSecond();
            mInodeCounter.set(checkpointInfo.COUNTER_INODE);
            mDependencyCounter.set(checkpointInfo.COUNTER_DEPENDENCY);
            break;
          }
          case InodeFile:
          case InodeFolder:
          case InodeRawTable: {
            Inode inode = (Inode) pair.getSecond();
            LOG.info("Putting " + inode);
            if (Math.abs(inode.getId()) > mInodeCounter.get()) {
              mInodeCounter.set(Math.abs(inode.getId()));
            }
            if (inode.getId() > 0) {
              mInodes.put(inode.getId(), inode);
              if (inode.getId() == 1) {
                mRoot = (InodeFolder) inode;
              }
            } else {
              mInodes.remove(- inode.getId());
            }
            break;
          }
          case Dependency: {
            Dependency dependency = (Dependency) pair.getSecond();
            LOG.info("Putting " + dependency);
            mDependencyCounter.set(Math.max(mDependencyCounter.get(), dependency.ID));
            mDependencies.put(dependency.ID, dependency);
            break;
          }
          case Undefined:
            CommonUtils.runtimeException("Corruptted data from " + fileName + 
                ". It has undefined data type.");
            break;
          default:
            CommonUtils.runtimeException("Corruptted data from " + fileName);
        }
      }
    }
  }

  private void recoveryFromLog() {
    recoveryFromFile(Config.MASTER_CHECKPOINT_FILE, "Master Checkpoint file ");
    recoveryFromFile(Config.MASTER_LOG_FILE, "Master Log file ");
  }

  public long registerWorker(NetAddress workerNetAddress, long totalBytes,
      long usedBytes, List<Integer> currentFileIds) {
    long id = 0;
    InetSocketAddress workerAddress =
        new InetSocketAddress(workerNetAddress.mHost, workerNetAddress.mPort);
    LOG.info("registerWorker(): WorkerNetAddress: " + workerAddress);

    synchronized (mWorkers) {
      if (mWorkerAddressToId.containsKey(workerAddress)) {
        id = mWorkerAddressToId.get(workerAddress);
        mWorkerAddressToId.remove(id);
        LOG.warn("The worker " + workerAddress + " already exists as id " + id + ".");
      }
      if (id != 0 && mWorkers.containsKey(id)) {
        WorkerInfo tWorkerInfo = mWorkers.get(id);
        mWorkers.remove(id);
        mLostWorkers.add(tWorkerInfo);
        LOG.warn("The worker with id " + id + " has been removed.");
      }
      id = START_TIME_NS_PREFIX + mWorkerCounter.incrementAndGet();
      WorkerInfo tWorkerInfo = new WorkerInfo(id, workerAddress, totalBytes);
      tWorkerInfo.updateUsedBytes(usedBytes);
      tWorkerInfo.updateFiles(true, currentFileIds);
      tWorkerInfo.updateLastUpdatedTimeMs();
      mWorkers.put(id, tWorkerInfo);
      mWorkerAddressToId.put(workerAddress, id);
      LOG.info("registerWorker(): " + tWorkerInfo);
    }

    synchronized (mRoot) {
      for (long fileId: currentFileIds) {
        Inode inode = mInodes.get(fileId);
        if (inode != null && inode.isFile()) {
          ((InodeFile) inode).addLocation(id, workerNetAddress);
        } else {
          LOG.warn("registerWorker failed to add fileId " + fileId);
        }
      }
    }

    return id;
  }

  public void renameFile(String srcPath, String dstPath) 
      throws FileAlreadyExistException, FileDoesNotExistException, InvalidPathException {
    synchronized (mRoot) {
      Inode inode = getInode(srcPath);
      if (inode == null) {
        throw new FileDoesNotExistException("Failed to rename: " + srcPath + " does not exist");
      }

      if (getInode(dstPath) != null) {
        throw new FileAlreadyExistException("Failed to rename: " + dstPath + " already exist");
      }

      String dstName = getName(dstPath);
      String dstFolderPath = dstPath.substring(0, dstPath.length() - dstName.length() - 1);

      // If we are renaming into the root folder
      if (dstFolderPath.isEmpty()) {
        dstFolderPath = "/";
      }

      Inode dstFolderInode = getInode(dstFolderPath);
      if (dstFolderInode == null || dstFolderInode.isFile()) {
        throw new FileDoesNotExistException("Failed to rename: " + dstFolderPath + 
            " does not exist.");
      }

      inode.setName(dstName);
      InodeFolder parent = (InodeFolder) mInodes.get(inode.getParentId());
      parent.removeChild(inode.getId());
      inode.setParentId(dstFolderInode.getId());
      ((InodeFolder) dstFolderInode).addChild(inode.getId());

      // TODO The following should be done atomically.
      mMasterLogWriter.appendAndFlush(parent);
      mMasterLogWriter.appendAndFlush(dstFolderInode);
      mMasterLogWriter.appendAndFlush(inode);
    }
  }

  public void reportLostFile(int fileId) {
    synchronized (mRoot) {
      Inode inode = mInodes.get(fileId);
      if (inode == null) {
        LOG.warn("Tachyon does not have file " +fileId);
      } else if (inode.isDirectory()) {
        LOG.warn("Reported file is a directory " + inode);
      } else {
        InodeFile iFile = (InodeFile) inode;
        int depId = iFile.getDependencyId();
        synchronized (mDependencies) {
          mLostFiles.add(fileId);
          if (depId == -1) {
            LOG.error("There is no dependency info for " + iFile + " . No recovery on that");
          } else {
            LOG.info("Reported file loss. Tachyon will recompute it: " + iFile.toString());

            Dependency dep = mDependencies.get(depId);
            dep.addLostFile(fileId);
            mMustRecomputeDependencies.add(depId);
          }
        }
      }
    }
  }

  public void unpinFile(int fileId) throws FileDoesNotExistException {
    // TODO Change meta data only. Data will be evicted from worker based on data replacement 
    // policy. TODO May change it to be active from V0.2
    LOG.info("unpinFile(" + fileId + ")");
    synchronized (mRoot) {
      Inode inode = mInodes.get(fileId);

      if (inode == null) {
        throw new FileDoesNotExistException("Failed to unpin " + fileId);
      }

      ((InodeFile) inode).setPin(false);
      synchronized (mIdPinList) {
        mIdPinList.remove(fileId);
      }

      mMasterLogWriter.appendAndFlush(inode);
    }
  }

  public Command workerHeartbeat(long workerId, long usedBytes, List<Integer> removedFileIds) {
    LOG.debug("workerHeartbeat(): WorkerId: " + workerId);
    synchronized (mWorkers) {
      WorkerInfo tWorkerInfo = mWorkers.get(workerId);

      if (tWorkerInfo == null) {
        LOG.info("worker_heartbeat(): Does not contain worker with ID " + workerId +
            " . Send command to let it re-register.");
        return new Command(CommandType.Register, ByteBuffer.allocate(0));
      }

      tWorkerInfo.updateUsedBytes(usedBytes);
      tWorkerInfo.updateFiles(false, removedFileIds);
      tWorkerInfo.updateLastUpdatedTimeMs();

      synchronized (mRoot) {
        for (int id : removedFileIds) {
          Inode inode = mInodes.get(id);
          if (inode == null) {
            LOG.error("Data " + id + " does not exist");
          } else if (inode.isFile()) {
            ((InodeFile) inode).removeLocation(workerId);
          }
        }
      }
    }

    return new Command(CommandType.Nothing, ByteBuffer.allocate(0));
  }

  private void writeCheckpoint() {
    LOG.info("Files recoveried from logs: ");
    MasterLogWriter checkpointWriter =
        new MasterLogWriter(Config.MASTER_CHECKPOINT_FILE + ".tmp");
    Queue<Inode> nodesQueue = new LinkedList<Inode>();
    synchronized (mRoot) {
      checkpointWriter.appendAndFlush(mRoot);
      nodesQueue.add(mRoot);
      while (!nodesQueue.isEmpty()) {
        InodeFolder tFolder = (InodeFolder) nodesQueue.poll();

        List<Integer> childrenIds = tFolder.getChildrenIds();
        for (int id : childrenIds) {
          Inode tInode = mInodes.get(id);
          checkpointWriter.appendAndFlush(tInode);
          if (tInode.isDirectory()) {
            nodesQueue.add(tInode);
          } else if (((InodeFile) tInode).isPin()) {
            synchronized (mIdPinList) {
              mIdPinList.add(tInode.getId());
            }
          }
        }
      }

      checkpointWriter.appendAndFlush(new CheckpointInfo(
          mInodeCounter.get(), mDependencyCounter.get()));
      checkpointWriter.close();

      CommonUtils.renameFile(Config.MASTER_CHECKPOINT_FILE + ".tmp", Config.MASTER_CHECKPOINT_FILE);

      CommonUtils.deleteFile(Config.MASTER_LOG_FILE);
    }
    LOG.info("Files recovery done. Current mInodeCounter: " + mInodeCounter.get());
  }
}