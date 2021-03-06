package edu.berkeley.sparrow.daemon.nodemonitor;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.configuration.Configuration;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;

import com.google.common.collect.Maps;

import edu.berkeley.sparrow.daemon.scheduler.SchedulerThrift;
import edu.berkeley.sparrow.daemon.util.Logging;
import edu.berkeley.sparrow.daemon.util.Network;
import edu.berkeley.sparrow.daemon.util.TResources;
import edu.berkeley.sparrow.daemon.util.ThriftClientPool;
import edu.berkeley.sparrow.thrift.GetTaskService;
import edu.berkeley.sparrow.thrift.GetTaskService.AsyncClient;
import edu.berkeley.sparrow.thrift.GetTaskService.AsyncClient.getTask_call;
import edu.berkeley.sparrow.thrift.TEnqueueTaskReservationsRequest;
import edu.berkeley.sparrow.thrift.TFullTaskId;
import edu.berkeley.sparrow.thrift.THostPort;
import edu.berkeley.sparrow.thrift.TResourceUsage;
import edu.berkeley.sparrow.thrift.TResourceVector;
import edu.berkeley.sparrow.thrift.TTaskLaunchSpec;
import edu.berkeley.sparrow.thrift.TUserGroupInfo;

/**
 * A TaskScheduler is a buffer that holds tasks between when they are launched on a
 * {@link NodeMonitor} and when they are passed to application backends.
 *
 * Each scheduler will implement a different policy determining when to launch tasks.
 *
 * Schedulers are required to be thread safe, as they will be accessed concurrently from
 * multiple threads.
 */
public abstract class TaskScheduler {
  protected class TaskSpec {
    public String appId;
    public TUserGroupInfo user;
    public String requestId;

    /**
     * ID of the task that previously ran in the slot this task is using. Used
     * to track how long it takes to fill an empty slot on a slave. Empty if this task was launched
     * immediately, because there were empty slots available on the slave.
     */
    public String previousRequestId;
    public String previousTaskId;

    public TResourceVector estimatedResources;
    public InetSocketAddress schedulerAddress;
    public InetSocketAddress appBackendAddress;

    public TTaskLaunchSpec taskSpec;

    public TaskSpec(TEnqueueTaskReservationsRequest request,
                           InetSocketAddress appBackendAddress) {
      appId = request.getAppId();
      user = request.getUser();
      requestId = request.getRequestId();
      estimatedResources = request.getEstimatedResources();
      schedulerAddress = new InetSocketAddress(request.getSchedulerAddress().getHost(),
                                               request.getSchedulerAddress().getPort());
      this.appBackendAddress = appBackendAddress;
      previousRequestId = "";
      previousTaskId = "";
    }
  }

  protected class ResourceInfo {
    /** Unlaunched tasks for which this resource information applies. */
    public int remainingTasks;

    /** Estimated resources. */
    public TResourceVector resources;

    public ResourceInfo(int tasks, TResourceVector resources) {
     remainingTasks = tasks;
     this.resources = resources;
    }
  }

  private final static Logger LOG = Logger.getLogger(TaskScheduler.class);
  private final static Logger AUDIT_LOG = Logging.getAuditLogger(TaskScheduler.class);
  private String ipAddress;

  protected TResourceVector capacity;
  protected Configuration conf;
  protected TResourceVector inUse = TResources.clone(TResources.none());
  private final BlockingQueue<TaskSpec> runnableTaskQueue =
      new LinkedBlockingQueue<TaskSpec>();
  private HashMap<String, ResourceInfo> resourcesPerRequest = Maps.newHashMap();

  private ThriftClientPool<GetTaskService.AsyncClient> getTaskClientPool =
      new ThriftClientPool<GetTaskService.AsyncClient>(
          new ThriftClientPool.GetTaskServiceMakerFactory());

  private THostPort nodeMonitorInternalAddress;

  /** Initialize the task scheduler, passing it the current available resources
   *  on the machine. */
  void initialize(TResourceVector capacity, Configuration conf, int nodeMonitorPort) {
    this.capacity = capacity;
    this.conf = conf;
    this.ipAddress = Network.getIPAddress(conf);
    nodeMonitorInternalAddress = new THostPort(Network.getHostName(conf), nodeMonitorPort);
  }

  /**
   * Get the next task available for launching. This will block until a task is available.
   */
  TaskSpec getNextTask() {
    TaskSpec task = null;
    try {
      task = runnableTaskQueue.take();
    } catch (InterruptedException e) {
      LOG.fatal(e);
    }
    addResourceInUse(task.estimatedResources);
    return task;
  }

  /**
   * Returns the current number of runnable tasks (for testing).
   */
  int runnableTasks() {
    return runnableTaskQueue.size();
  }

  void tasksFinished(List<TFullTaskId> finishedTasks) {
    for (TFullTaskId t : finishedTasks) {
      AUDIT_LOG.info(Logging.auditEventString("task_completed", t.getRequestId(), t.getTaskId()));
      taskCompleted(t.getRequestId(), t.getRequestId(), t.getTaskId());
    }
  }

  void noTaskForRequest(TaskSpec taskReservation) {
    AUDIT_LOG.info(Logging.auditEventString("node_monitor_get_task_no_task",
                                            taskReservation.requestId,
                                            taskReservation.previousRequestId,
                                            taskReservation.previousTaskId));
    taskCompleted(taskReservation.requestId, taskReservation.previousRequestId,
                  taskReservation.previousTaskId);
  }

  /**
   * Signals that a task associated with the given requestId has completed. lastExecutedTaskId
   * and lastExecutedTaskRequestId describe the last task that executed (as opposed to tasks that
   * the node monitor attempted to get from the scheduler, but the scheduler didn't return a task
   * because all tasks for the job had been executed). Used to determine how long it takes the node
   * monitor to launch a task from the queue. Empty strings indicate that the task was launched
   * directly from the queue (so there was no immediately prevoius task).
   */
  private synchronized void taskCompleted(String requestId, String lastExecutedTaskRequestId,
                                          String lastExecutedTaskId) {
    LOG.debug(Logging.functionCall(requestId));
    ResourceInfo resourceInfo = resourcesPerRequest.get(requestId);
    if (resourceInfo == null) {
      LOG.error("Missing resources for request: " + requestId);
      resourceInfo = new ResourceInfo(1, TResources.createResourceVector(0, 1));
    }
    resourceInfo.remainingTasks--;
    if (resourceInfo.remainingTasks == 0) {
      LOG.debug("Deleting resources for request " + requestId);
      resourcesPerRequest.remove(requestId);
    }
    freeResourceInUse(resourceInfo.resources);
    handleTaskCompleted(requestId, lastExecutedTaskRequestId, lastExecutedTaskId);
  }

  protected void makeTaskRunnable(TaskSpec task) {
    LOG.debug("Attempting to get task for request " + task.requestId +
              " (previous task: " + task.previousTaskId + ")");
    GetTaskService.AsyncClient getTaskClient;
    InetSocketAddress newAddress = new InetSocketAddress(
        task.schedulerAddress.getHostName(), SchedulerThrift.DEFAULT_GET_TASK_PORT);
    try {
      getTaskClient = getTaskClientPool.borrowClient(newAddress);
    } catch (Exception e) {
      LOG.fatal("Unable to create client to contact scheduler at " +
          newAddress.toString() + ":" + e);
      return;
    }
    try {
      LOG.debug("Attempting to get task from scheduler at " +
                nodeMonitorInternalAddress.toString() + " for request " + task.requestId);
      AUDIT_LOG.debug(Logging.auditEventString("node_monitor_get_task", task.requestId,
                                               nodeMonitorInternalAddress.getHost()));
      getTaskClient.getTask(task.requestId, nodeMonitorInternalAddress,
                              new GetTaskCallback(task, newAddress));
    } catch (TException e) {
      LOG.error("Unable to getTask() from scheduler at " +
          newAddress.toString() + ":" + e);
    }
  }

  public synchronized void submitTaskReservations(TEnqueueTaskReservationsRequest request,
                                                  InetSocketAddress appBackendAddress) {
    ResourceInfo resourceInfo = new ResourceInfo(request.getNumTasks(),
                                                 request.getEstimatedResources());
    resourcesPerRequest.put(request.getRequestId(), resourceInfo);
    LOG.debug("Added resource info for request " + request.getRequestId() + " with " +
              resourceInfo.remainingTasks + " tasks.");
    for (int i = 0; i < request.getNumTasks(); ++i) {
      LOG.debug("Creating reservation " + i + " for request " + request.getRequestId());
      TaskSpec reservation = new TaskSpec(request, appBackendAddress);
      int queuedReservations = handleSubmitTaskReservation(reservation);
      AUDIT_LOG.info(Logging.auditEventString("reservation_enqueued", ipAddress, request.requestId,
                                              queuedReservations));
    }
  }

  protected synchronized void addResourceInUse(TResourceVector nowInUse) {
    TResources.addTo(inUse, nowInUse);
  }

  protected synchronized void freeResourceInUse(TResourceVector nowFreed) {
    TResources.subtractFrom(inUse, nowFreed);
  }

  /**
   * Return the quantity of free resources on the node. Free resources are determined
   * by subtracting the currently used resources and currently runnable resources from
   * the node's capacity.
   */
  protected synchronized TResourceVector getFreeResources() {
    TResourceVector free = TResources.subtract(capacity, inUse);
    TResourceVector reserved = TResources.none();
    for (TaskSpec t: runnableTaskQueue) {
      reserved = TResources.add(reserved, t.estimatedResources);
    }
    return TResources.subtract(free, reserved);
  }

  // TASK SCHEDULERS MUST IMPLEMENT THE FOLLOWING

  /**
   * Handles a task reservation. Returns the number of queued reservations.
   */
  abstract int handleSubmitTaskReservation(TaskSpec taskReservation);

  /**
   * Signal that a given task has completed.
   */
  protected abstract void handleTaskCompleted(String requestId, String lastExecutedTaskRequestId,
                                              String lastExecutedTaskId);

  /**
   * Returns the current resource usage. If the resource usage is equal to the
   * machines capacity, this will include the queue length for appId.
   */
  abstract TResourceUsage getResourceUsage(String appId);

  private class GetTaskCallback implements AsyncMethodCallback<getTask_call> {
    private TaskSpec task;
    private InetSocketAddress getTaskAddress;

    public GetTaskCallback(TaskSpec taskReservation, InetSocketAddress getTaskAddress) {
      this.task = taskReservation;
      this.getTaskAddress = getTaskAddress;
    }

    @Override
    public void onComplete(getTask_call response) {
      LOG.debug(Logging.functionCall(response));
      try {
        getTaskClientPool.returnClient(getTaskAddress, (AsyncClient) response.getClient());
      } catch (Exception e) {
        LOG.error("Error getting client from scheduler client pool: " + e.getMessage());
        return;
      }
      List<TTaskLaunchSpec> taskLaunchSpecs;
      try {
        taskLaunchSpecs = response.getResult();
      } catch (TException e) {
        LOG.error("Unable to read result of calling getTask() on scheduler " +
                  task.schedulerAddress.toString() + ": " + e);
        noTaskForRequest(task);
        return;
      }

      if (taskLaunchSpecs.isEmpty()) {
        LOG.debug("Didn't receive a task for request " + task.requestId);
        noTaskForRequest(task);
        return;
      }

      if (taskLaunchSpecs.size() > 1) {
        LOG.warn("Received " + taskLaunchSpecs +
                 " task launch specifications; ignoring all but the first one.");
      }
      task.taskSpec = taskLaunchSpecs.get(0);
      LOG.debug("Received task for request " + task.requestId + ", task " +
                task.taskSpec.getTaskId());

      try {
        runnableTaskQueue.put(task);
      } catch (InterruptedException e) {
        LOG.fatal(e);
      }
    }

    @Override
    public void onError(Exception exception) {
      // Do not return error client to pool.
      exception.printStackTrace();
      LOG.error("Error executing getTask() RPC:" + exception.getStackTrace().toString() +
                exception.toString());
    }
  }
}
