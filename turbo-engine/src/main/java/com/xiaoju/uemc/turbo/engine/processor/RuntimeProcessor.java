package com.xiaoju.uemc.turbo.engine.processor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.xiaoju.uemc.turbo.engine.param.RollbackTaskParam;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.xiaoju.uemc.modules.support.jedis.RedisUtils;
import com.xiaoju.uemc.turbo.engine.bo.*;
import com.xiaoju.uemc.turbo.engine.common.*;
import com.xiaoju.uemc.turbo.engine.dao.FlowDeploymentDAO;
import com.xiaoju.uemc.turbo.engine.dao.InstanceDataDAO;
import com.xiaoju.uemc.turbo.engine.dao.NodeInstanceDAO;
import com.xiaoju.uemc.turbo.engine.dao.ProcessInstanceDAO;
import com.xiaoju.uemc.turbo.engine.dto.*;
import com.xiaoju.uemc.turbo.engine.entity.FlowDeploymentPO;
import com.xiaoju.uemc.turbo.engine.entity.FlowInstancePO;
import com.xiaoju.uemc.turbo.engine.entity.InstanceDataPO;
import com.xiaoju.uemc.turbo.engine.entity.NodeInstancePO;
import com.xiaoju.uemc.turbo.engine.exception.ProcessException;
import com.xiaoju.uemc.turbo.engine.exception.ReentrantException;
import com.xiaoju.uemc.turbo.engine.executor.FlowExecutor;
import com.xiaoju.uemc.turbo.engine.model.FlowElement;
import com.xiaoju.uemc.turbo.engine.model.InstanceData;
import com.xiaoju.uemc.turbo.engine.param.CommitTaskParam;
import com.xiaoju.uemc.turbo.engine.param.StartProcessParam;
import com.xiaoju.uemc.turbo.engine.util.FlowModelUtil;
import com.xiaoju.uemc.turbo.engine.util.InstanceDataUtil;
import com.xiaoju.uemc.turbo.engine.validator.ParamValidator;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * Created by Stefanie on 2019/12/1.
 */
@Component
public class RuntimeProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeProcessor.class);

    @Resource
    private FlowDeploymentDAO flowDeploymentDAO;

    @Resource
    private ProcessInstanceDAO processInstanceDAO;

    @Resource
    private NodeInstanceDAO nodeInstanceDAO;

    @Resource
    private InstanceDataDAO instanceDataDAO;

    @Resource
    private FlowExecutor flowExecutor;


    private final RedisUtils redisClient = RedisUtils.getInstance();

    ////////////////////////////////////////startProcess////////////////////////////////////////

    public StartProcessResult startProcess(StartProcessParam startProcessParam) {
        RuntimeContext runtimeContext = null;
        try {
            //1.param validate
            ParamValidator.validate(startProcessParam);

            //2.getFlowInfo
            FlowInfo flowInfo = getFlowInfo(startProcessParam);

            //3.init context for runtime
            runtimeContext = buildStartProcessContext(flowInfo, startProcessParam.getVariables());

            //4.process
            flowExecutor.execute(runtimeContext);

            //5.build result
            return buildStartProcessDTO(runtimeContext);
        } catch (ProcessException e) {
            if (!ErrorEnum.isSuccess(e.getErrNo())) {
                LOGGER.warn("startProcess ProcessException.||startProcessParam={}||runtimeContext={}, ",
                        startProcessParam, runtimeContext, e);
            }
            return buildStartProcessDTO(runtimeContext, e);
        }
    }

    private FlowInfo getFlowInfo(StartProcessParam startProcessParam) throws ProcessException {
        if (StringUtils.isNotBlank(startProcessParam.getFlowDeployId())) {
            return getFlowInfoByFlowDeployId(startProcessParam.getFlowDeployId());
        } else {
            return getFlowInfoByFlowModuleId(startProcessParam.getFlowModuleId());
        }
    }

    /**
     * Init runtimeContext for startProcess:
     * 1.flowInfo: flowDeployId, flowModuleId, tenantId, flowModel(FlowElementList)
     * 2.variables: inputDataList fr. param
     */
    private RuntimeContext buildStartProcessContext(FlowInfo flowInfo, List<InstanceData> variables) {
        return buildRuntimeContext(flowInfo, variables);
    }

    private StartProcessResult buildStartProcessDTO(RuntimeContext runtimeContext) {
        StartProcessResult startProcessDTO = new StartProcessResult();
        BeanUtils.copyProperties(runtimeContext, startProcessDTO);
        return (StartProcessResult) fillRuntimeDTO(startProcessDTO, runtimeContext);
    }

    private StartProcessResult buildStartProcessDTO(RuntimeContext runtimeContext, ProcessException e) {
        StartProcessResult startProcessDTO = new StartProcessResult();
        BeanUtils.copyProperties(runtimeContext, startProcessDTO);
        return (StartProcessResult) fillRuntimeDTO(startProcessDTO, runtimeContext, e);
    }

    ////////////////////////////////////////commit////////////////////////////////////////

    public CommitTaskResult commit(CommitTaskParam commitTaskParam) {
        RuntimeContext runtimeContext = null;
        try {
            //1.param validate
            ParamValidator.validate(commitTaskParam);

            //2.get flowInstance
            FlowInstanceBO flowInstanceBO = getFlowInstanceBO(commitTaskParam.getFlowInstanceId());
            if (flowInstanceBO == null) {
                LOGGER.warn("commit failed: cannot find flowInstanceBO.||flowInstanceId={}", commitTaskParam.getFlowInstanceId());
                throw new ProcessException(ErrorEnum.GET_FLOW_INSTANCE_FAILED);
            }

            //3.check status
            if (flowInstanceBO.getStatus() == FlowInstanceStatus.TERMINATED) {
                LOGGER.warn("commit failed: flowInstance has been completed.||commitTaskParam={}", commitTaskParam);
                throw new ProcessException(ErrorEnum.COMMIT_REJECTRD);
            }
            if (flowInstanceBO.getStatus() == FlowInstanceStatus.COMPLETED) {
                LOGGER.warn("commit: reentrant process.||commitTaskParam={}", commitTaskParam);
                throw new ReentrantException(ErrorEnum.REENTRANT_WARNING);
            }
            String flowDeployId = flowInstanceBO.getFlowDeployId();

            //4.getFlowInfo
            FlowInfo flowInfo = getFlowInfoByFlowDeployId(flowDeployId);

            //5.init runtimeContext
            runtimeContext = buildCommitContext(commitTaskParam, flowInfo, flowInstanceBO.getStatus());

            //6.process
            flowExecutor.commit(runtimeContext);

            //7.build result
            return buildCommitTaskDTO(runtimeContext);
        } catch (ProcessException e) {
            if (!ErrorEnum.isSuccess(e.getErrNo())) {
                LOGGER.warn("commit ProcessException.||commitTaskParam={}||runtimeContext={}, ", commitTaskParam, runtimeContext, e);
            }
            return buildCommitTaskDTO(runtimeContext, e);
        }
    }

    private RuntimeContext buildCommitContext(CommitTaskParam commitTaskParam, FlowInfo flowInfo, int flowInstanceStatus) {
        //1. set flow info
        RuntimeContext runtimeContext = buildRuntimeContext(flowInfo, commitTaskParam.getVariables());

        //2. init flowInstance with flowInstanceId
        runtimeContext.setFlowInstanceId(commitTaskParam.getFlowInstanceId());
        runtimeContext.setFlowInstanceStatus(flowInstanceStatus);

        //3. set suspendNodeInstance with taskInstance in param
        NodeInstanceBO suspendNodeInstance = new NodeInstanceBO();
        suspendNodeInstance.setNodeInstanceId(commitTaskParam.getTaskInstanceId());
        runtimeContext.setSuspendNodeInstance(suspendNodeInstance);

        return runtimeContext;
    }

    private CommitTaskResult buildCommitTaskDTO(RuntimeContext runtimeContext) {
        CommitTaskResult commitTaskResult = new CommitTaskResult();
        return (CommitTaskResult) fillRuntimeDTO(commitTaskResult, runtimeContext);
    }

    private CommitTaskResult buildCommitTaskDTO(RuntimeContext runtimeContext, ProcessException e) {
        CommitTaskResult commitTaskResult = new CommitTaskResult();
        return (CommitTaskResult) fillRuntimeDTO(commitTaskResult, runtimeContext, e);
    }

    ////////////////////////////////////////recall////////////////////////////////////////

    /**
     * Recall: rollback node process from param.taskInstance to the last taskInstance to suspend
     *
     * @param rollbackTaskParam: flowInstanceId + taskInstanceId(nodeInstanceId)
     * @return recallTaskDTO: runtimeDTO, flowInstanceId + activeTaskInstance(nodeInstanceId,nodeKey,status) + dataMap
     * @throws Exception
     */
    public RollbackTaskResult rollback(RollbackTaskParam rollbackTaskParam) {
        RuntimeContext runtimeContext = null;
        try {
            //1.param validate
            ParamValidator.validate(rollbackTaskParam);

            //2.get flowInstance
            FlowInstanceBO flowInstanceBO = getFlowInstanceBO(rollbackTaskParam.getFlowInstanceId());
            if (flowInstanceBO == null) {
                LOGGER.warn("recall failed: flowInstanceBO is null.||flowInstanceId={}", rollbackTaskParam.getFlowInstanceId());
                throw new ProcessException(ErrorEnum.GET_FLOW_INSTANCE_FAILED);
            }

            //3.check status
            if (flowInstanceBO.getStatus() != FlowInstanceStatus.RUNNING) {
                LOGGER.warn("recall failed: invalid status to recall.||rollbackTaskParam={}||status={}", rollbackTaskParam, flowInstanceBO.getStatus());
                throw new ProcessException(ErrorEnum.ROLLBACK_REJECTRD);
            }
            String flowDeployId = flowInstanceBO.getFlowDeployId();

            //4.getFlowInfo
            FlowInfo flowInfo = getFlowInfoByFlowDeployId(flowDeployId);

            //5.init runtimeContext
            runtimeContext = buildRollbackContext(rollbackTaskParam, flowInfo, flowInstanceBO.getStatus());

            //6.process
            flowExecutor.rollback(runtimeContext);

            //7.build result
            return buildRollbackTaskDTO(runtimeContext);
        } catch (ProcessException e) {
            if (!ErrorEnum.isSuccess(e.getErrNo())) {
                LOGGER.warn("recall ProcessException.||rollbackTaskParam={}||runtimeContext={}, ", rollbackTaskParam, runtimeContext, e);
            }
            return buildRollbackTaskDTO(runtimeContext, e);
        }
    }

    private RuntimeContext buildRollbackContext(RollbackTaskParam rollbackTaskParam, FlowInfo flowInfo, int flowInstanceStatus) {
        //1. set flow info
        RuntimeContext runtimeContext = buildRuntimeContext(flowInfo);

        //2. init flowInstance with flowInstanceId
        runtimeContext.setFlowInstanceId(rollbackTaskParam.getFlowInstanceId());
        runtimeContext.setFlowInstanceStatus(flowInstanceStatus);

        //3. set suspendNodeInstance with taskInstance in param
        NodeInstanceBO suspendNodeInstance = new NodeInstanceBO();
        suspendNodeInstance.setNodeInstanceId(rollbackTaskParam.getTaskInstanceId());
        runtimeContext.setSuspendNodeInstance(suspendNodeInstance);

        return runtimeContext;
    }

    private RollbackTaskResult buildRollbackTaskDTO(RuntimeContext runtimeContext) {
        RollbackTaskResult rollbackTaskResult = new RollbackTaskResult();
        return (RollbackTaskResult) fillRuntimeDTO(rollbackTaskResult, runtimeContext);
    }

    private RollbackTaskResult buildRollbackTaskDTO(RuntimeContext runtimeContext, ProcessException e) {
        RollbackTaskResult rollbackTaskResult = new RollbackTaskResult();
        return (RollbackTaskResult) fillRuntimeDTO(rollbackTaskResult, runtimeContext, e);
    }

    ////////////////////////////////////////terminate////////////////////////////////////////

    public TerminateResult terminateProcess(String flowInstanceId) {
        TerminateResult terminateDTO;
        try {
            int flowInstanceStatus;

            FlowInstancePO flowInstancePO = processInstanceDAO.selectByFlowInstanceId(flowInstanceId);
            if (flowInstancePO.getStatus() == FlowInstanceStatus.COMPLETED) {
                LOGGER.warn("terminateProcess: flowInstance is completed.||flowInstanceId={}", flowInstanceId);
                flowInstanceStatus = FlowInstanceStatus.COMPLETED;
            } else {
                processInstanceDAO.updateStatus(flowInstancePO, FlowInstanceStatus.TERMINATED);
                flowInstanceStatus = FlowInstanceStatus.TERMINATED;
            }

            terminateDTO = new TerminateResult(ErrorEnum.SUCCESS);
            terminateDTO.setFlowInstanceId(flowInstanceId);
            terminateDTO.setStatus(flowInstanceStatus);
        } catch (Exception e) {
            LOGGER.error("terminateProcess exception.||flowInstanceId={}, ", flowInstanceId, e);
            terminateDTO = new TerminateResult(ErrorEnum.SYSTEM_ERROR);
            terminateDTO.setFlowInstanceId(flowInstanceId);
        } finally {
            //the status is unknown while exception occurs, clear it as well
            redisClient.del(RedisConstants.FLOW_INSTANCE + flowInstanceId);
        }
        return terminateDTO;
    }

    ////////////////////////////////////////updateInstanceData////////////////////////////////////////
    public CommonResult updateInstanceData(String flowInstanceId, Map<String, InstanceData> dataMap) throws Exception {
        InstanceDataPO instanceDataPO = instanceDataDAO.selectRecentOne(flowInstanceId);
        CommonResult commonResult = new CommonResult(ErrorEnum.SUCCESS);
        return commonResult;
    }

    ////////////////////////////////////////getHistoryUserTaskList////////////////////////////////////////

    public NodeInstanceListResult getHistoryUserTaskList(String flowInstanceId) {

        //1.get nodeInstanceList by flowInstanceId order by id desc
        List<NodeInstancePO> historyNodeInstanceList = getDescHistoryNodeInstanceList(flowInstanceId);

        //2.init dto
        NodeInstanceListResult historyListDTO = new NodeInstanceListResult(ErrorEnum.SUCCESS);
        historyListDTO.setNodeInstanceDTOList(Lists.newArrayList());

        try {

            if (CollectionUtils.isEmpty(historyNodeInstanceList)) {
                LOGGER.warn("getHistoryUserTaskList: historyNodeInstanceList is empty.||flowInstanceId={}", flowInstanceId);
                return historyListDTO;
            }

            //3.get flow info
            String flowDeployId = historyNodeInstanceList.get(0).getFlowDeployId();
            Map<String, FlowElement> flowElementMap = getFlowElementMap(flowDeployId);

            //4.pick out userTask and build dto
            List<NodeInstance> userTaskDTOList = historyListDTO.getNodeInstanceDTOList();//empty list

            for (NodeInstancePO nodeInstancePO : historyNodeInstanceList) {
                //ignore noneffective nodeInstance
                if (!isEffectiveNodeInstance(nodeInstancePO.getStatus())) {
                    continue;
                }

                //ignore un-userTask instance
                if (!isUserTask(nodeInstancePO.getNodeKey(), flowElementMap)) {
                    continue;
                }

                //build effective userTask instance DTO
                NodeInstance nodeInstanceDTO = new NodeInstance();
                //set instanceId & status
                BeanUtils.copyProperties(nodeInstancePO, nodeInstanceDTO);

                //set ElementModel info
                FlowElement flowElement = FlowModelUtil.getFlowElement(flowElementMap, nodeInstancePO.getNodeKey());
                nodeInstanceDTO.setModelKey(flowElement.getKey());
                nodeInstanceDTO.setModelName(FlowModelUtil.getElementName(flowElement));
                if (MapUtils.isNotEmpty(flowElement.getProperties())) {
                    nodeInstanceDTO.setProperties(flowElement.getProperties());
                } else {
                    nodeInstanceDTO.setProperties(Maps.newHashMap());
                }
                userTaskDTOList.add(nodeInstanceDTO);
            }
        } catch (ProcessException e) {
            historyListDTO.setErrCode(e.getErrNo());
            historyListDTO.setErrMsg(e.getErrMsg());
        }
        return historyListDTO;
    }

    private Map<String, FlowElement> getFlowElementMap(String flowDeployId) throws ProcessException {
        FlowInfo flowInfo = getFlowInfoByFlowDeployId(flowDeployId);
        String flowModel = flowInfo.getFlowModel();
        return FlowModelUtil.getFlowElementMap(flowModel);
    }

    private boolean isEffectiveNodeInstance(int status) {
        return status == NodeInstanceStatus.COMPLETED || status == NodeInstanceStatus.ACTIVE;
    }

    private boolean isUserTask(String nodeKey, Map<String, FlowElement> flowElementMap) throws ProcessException {
        if (!flowElementMap.containsKey(nodeKey)) {
            LOGGER.warn("isUserTask: invalid nodeKey which is not in flowElementMap.||nodeKey={}||flowElementMap={}",
                    nodeKey, flowElementMap);
            throw new ProcessException(ErrorEnum.GET_NODE_FAILED);
        }
        FlowElement flowElement = flowElementMap.get(nodeKey);
        return flowElement.getType() == FlowElementType.USER_TASK;
    }

    ////////////////////////////////////////getHistoryElementList////////////////////////////////////////

    public ElementInstanceListResult getHistoryElementList(String flowInstanceId) {
        //1.getHistoryNodeList
        List<NodeInstancePO> historyNodeInstanceList = getHistoryNodeInstanceList(flowInstanceId);

        //2.init DTO
        ElementInstanceListResult elementInstanceListResult = new ElementInstanceListResult(ErrorEnum.SUCCESS);
        elementInstanceListResult.setElementInstanceList(Lists.newArrayList());

        try {
            if (CollectionUtils.isEmpty(historyNodeInstanceList)) {
                LOGGER.warn("getHistoryElementList: historyNodeInstanceList is empty.||flowInstanceId={}", flowInstanceId);
                return elementInstanceListResult;
            }

            //3.get flow info
            String flowDeployId = historyNodeInstanceList.get(0).getFlowDeployId();
            Map<String, FlowElement> flowElementMap = getFlowElementMap(flowDeployId);

            //4.calculate elementInstanceMap: key=elementKey, value(lasted)=ElementInstance(elementKey, status)
            List<ElementInstance> elementInstanceList = elementInstanceListResult.getElementInstanceList();
            for (NodeInstancePO nodeInstancePO : historyNodeInstanceList) {
                String nodeKey = nodeInstancePO.getNodeKey();
                String sourceNodeKey = nodeInstancePO.getSourceNodeKey();
                int nodeStatus = nodeInstancePO.getStatus();

                //4.1 build the source sequenceFlow instance DTO
                if (StringUtils.isNotBlank(sourceNodeKey)) {
                    FlowElement sourceFlowElement = FlowModelUtil.getSequenceFlow(flowElementMap, sourceNodeKey, nodeKey);
                    if (sourceFlowElement == null) {
                        LOGGER.error("getHistoryElementList failed: sourceFlowElement is null."
                                + "||nodeKey={}||sourceNodeKey={}||flowElementMap={}", nodeKey, sourceNodeKey, flowElementMap);
                        throw new ProcessException(ErrorEnum.MODEL_UNKNOWN_ELEMENT_KEY);
                    }

                    //build ElementInstance
                    int sourceSequenceFlowStatus = nodeStatus;
                    if (nodeStatus == NodeInstanceStatus.ACTIVE) {
                        sourceSequenceFlowStatus = NodeInstanceStatus.COMPLETED;
                    }
                    ElementInstance sequenceFlowInstanceDTO = new ElementInstance(sourceFlowElement.getKey(), sourceSequenceFlowStatus);
                    elementInstanceList.add(sequenceFlowInstanceDTO);
                }

                //4.2 build nodeInstance DTO
                ElementInstance nodeInstanceDTO = new ElementInstance(nodeKey, nodeStatus);
                elementInstanceList.add(nodeInstanceDTO);
            }
        } catch (ProcessException e) {
            elementInstanceListResult.setErrCode(e.getErrNo());
            elementInstanceListResult.setErrMsg(e.getErrMsg());
        }
        return elementInstanceListResult;
    }

    private List<NodeInstancePO> getHistoryNodeInstanceList(String flowInstanceId) {
        return nodeInstanceDAO.selectByFlowInstanceId(flowInstanceId);
    }

    private List<NodeInstancePO> getDescHistoryNodeInstanceList(String flowInstanceId) {
        return nodeInstanceDAO.selectDescByFlowInstanceId(flowInstanceId);
    }

    public NodeInstanceResult getNodeInstance(String flowInstanceId, String nodeInstanceId) {
        NodeInstanceResult nodeInstanceResult = new NodeInstanceResult();
        try {
            NodeInstancePO nodeInstancePO = nodeInstanceDAO.selectByNodeInstanceId(flowInstanceId, nodeInstanceId);
            String flowDeployId = nodeInstancePO.getFlowDeployId();
            Map<String, FlowElement> flowElementMap = getFlowElementMap(flowDeployId);
            NodeInstance nodeInstanceDTO = new NodeInstance();
            BeanUtils.copyProperties(nodeInstancePO, nodeInstanceDTO);
            FlowElement flowElement = FlowModelUtil.getFlowElement(flowElementMap, nodeInstancePO.getNodeKey());
            nodeInstanceDTO.setModelKey(flowElement.getKey());
            nodeInstanceDTO.setModelName(FlowModelUtil.getElementName(flowElement));
            if (MapUtils.isNotEmpty(flowElement.getProperties())) {
                nodeInstanceDTO.setProperties(flowElement.getProperties());
            } else {
                nodeInstanceDTO.setProperties(Maps.newHashMap());
            }
            nodeInstanceResult.setNodeInstance(nodeInstanceDTO);
            nodeInstanceResult.setErrCode(ErrorEnum.SUCCESS.getErrNo());
            nodeInstanceResult.setErrMsg(ErrorEnum.SUCCESS.getErrMsg());
        } catch (ProcessException e) {
            nodeInstanceResult.setErrCode(e.getErrNo());
            nodeInstanceResult.setErrMsg(e.getErrMsg());
        }
        return nodeInstanceResult;
    }

    ////////////////////////////////////////getInstanceData////////////////////////////////////////
    public InstanceDataListResult getInstanceData(String flowInstanceId) {
        InstanceDataPO instanceDataPO = instanceDataDAO.selectRecentOne(flowInstanceId);

        TypeReference<List<InstanceData>> typeReference = new TypeReference<List<InstanceData>>() {
        };
        List<InstanceData> instanceDataList = JSONObject.parseObject(instanceDataPO.getInstanceData(), typeReference);
        if (CollectionUtils.isEmpty(instanceDataList)) {
            instanceDataList =  Lists.newArrayList();
        }

        InstanceDataListResult instanceDataListResult = new InstanceDataListResult();
        instanceDataListResult.setVariables(instanceDataList);
        instanceDataListResult.setErrCode(ErrorEnum.SUCCESS.getErrNo());
        instanceDataListResult.setErrMsg(ErrorEnum.SUCCESS.getErrMsg());
        return instanceDataListResult;
    }

    ////////////////////////////////////////common////////////////////////////////////////

    private FlowInfo getFlowInfoByFlowDeployId(String flowDeployId) throws ProcessException {

        FlowDeploymentPO flowDeploymentPO = flowDeploymentDAO.selectByDeployId(flowDeployId);
        if (flowDeploymentPO == null) {
            LOGGER.warn("getFlowInfoByFlowDeployId failed.||flowDeployId={}", flowDeployId);
            throw new ProcessException(ErrorEnum.GET_FLOW_DEPLOYMENT_FAILED);
        }
        FlowInfo flowInfo = new FlowInfo();
        BeanUtils.copyProperties(flowDeploymentPO, flowInfo);

        return flowInfo;
    }

    private FlowInfo getFlowInfoByFlowModuleId(String flowModuleId) throws ProcessException {
        //get from db directly
        FlowDeploymentPO flowDeploymentPO = flowDeploymentDAO.selectRecentByFlowModuleId(flowModuleId);
        if (flowDeploymentPO == null) {
            LOGGER.warn("getFlowInfoByFlowModuleId failed.||flowModuleId={}", flowModuleId);
            throw new ProcessException(ErrorEnum.GET_FLOW_DEPLOYMENT_FAILED);
        }

        FlowInfo flowInfo = new FlowInfo();
        BeanUtils.copyProperties(flowDeploymentPO, flowInfo);

        //set into cache
        redisClient.setex(RedisConstants.FLOW_INFO + flowInfo.getFlowDeployId(), RedisConstants.FLOW_EXPIRED_SECOND,
                JSON.toJSONString(flowInfo));
        return flowInfo;
    }

    private FlowInstanceBO getFlowInstanceBO(String flowInstanceId) throws ProcessException {
        //get from cache firstly
        String redisKey = RedisConstants.FLOW_INSTANCE + flowInstanceId;
        String flowInstanceStr = redisClient.get(redisKey);
        if (StringUtils.isNotBlank(flowInstanceStr)) {
            LOGGER.info("getFlowInstanceBO from cache.||flowInstanceId={}||flowInstanceStr={}", flowInstanceId, flowInstanceStr);
            return JSONObject.parseObject(flowInstanceStr, FlowInstanceBO.class);
        }

        //get from db
        FlowInstancePO flowInstancePO = processInstanceDAO.selectByFlowInstanceId(flowInstanceId);
        if (flowInstancePO == null) {
            LOGGER.warn("getFlowInstancePO failed: cannot find flowInstancePO from db.||flowInstanceId={}", flowInstanceId);
            throw new ProcessException(ErrorEnum.GET_FLOW_INSTANCE_FAILED);
        }
        FlowInstanceBO flowInstanceBO = new FlowInstanceBO();
        BeanUtils.copyProperties(flowInstancePO, flowInstanceBO);

        //set into cache
        redisClient.setex(RedisConstants.FLOW_INSTANCE + flowInstanceBO.getFlowInstanceId(),
                RedisConstants.FLOW_INSTANCE_EXPIRED_SECOND, JSON.toJSONString(flowInstanceBO));
        return flowInstanceBO;
    }

    private RuntimeContext buildRuntimeContext(FlowInfo flowInfo) {
        RuntimeContext runtimeContext = new RuntimeContext();
        BeanUtils.copyProperties(flowInfo, runtimeContext);
        runtimeContext.setFlowElementMap(FlowModelUtil.getFlowElementMap(flowInfo.getFlowModel()));
        return runtimeContext;
    }

    private RuntimeContext buildRuntimeContext(FlowInfo flowInfo, List<InstanceData> variables) {
        RuntimeContext runtimeContext = buildRuntimeContext(flowInfo);
        Map<String, InstanceData> instanceDataMap = InstanceDataUtil.getInstanceDataMap(variables);
        runtimeContext.setInstanceDataMap(instanceDataMap);
        return runtimeContext;
    }

    private RuntimeResult fillRuntimeDTO(RuntimeResult runtimeResult, RuntimeContext runtimeContext) {
        if (runtimeContext.getProcessStatus() == ProcessStatus.SUCCESS) {
            return fillRuntimeDTO(runtimeResult, runtimeContext, ErrorEnum.SUCCESS);
        }
        return fillRuntimeDTO(runtimeResult, runtimeContext, ErrorEnum.FAILED);
    }

    private RuntimeResult fillRuntimeDTO(RuntimeResult runtimeResult, RuntimeContext runtimeContext, ErrorEnum errorEnum) {
        return fillRuntimeDTO(runtimeResult, runtimeContext, errorEnum.getErrNo(), errorEnum.getErrMsg());
    }

    private RuntimeResult fillRuntimeDTO(RuntimeResult runtimeResult, RuntimeContext runtimeContext, ProcessException e) {
        return fillRuntimeDTO(runtimeResult, runtimeContext, e.getErrNo(), e.getErrMsg());
    }

    private RuntimeResult fillRuntimeDTO(RuntimeResult runtimeResult, RuntimeContext runtimeContext, int errNo, String errMsg) {
        runtimeResult.setErrCode(errNo);
        runtimeResult.setErrMsg(errMsg);

        if (runtimeContext != null) {
            runtimeResult.setFlowInstanceId(runtimeContext.getFlowInstanceId());
            runtimeResult.setStatus(runtimeContext.getFlowInstanceStatus());
            runtimeResult.setActiveTaskInstance(buildActiveTaskInstanceDTO(runtimeContext.getSuspendNodeInstance(), runtimeContext));
            runtimeResult.setVariables(InstanceDataUtil.getInstanceDataList(runtimeContext.getInstanceDataMap()));
        }
        return runtimeResult;
    }

    private NodeInstance buildActiveTaskInstanceDTO(NodeInstanceBO nodeInstanceBO, RuntimeContext runtimeContext) {
        NodeInstance activeNodeInstanceDTO = new NodeInstance();
        BeanUtils.copyProperties(nodeInstanceBO, activeNodeInstanceDTO);
        activeNodeInstanceDTO.setModelKey(nodeInstanceBO.getNodeKey());
        FlowElement flowElement = runtimeContext.getFlowElementMap().get(nodeInstanceBO.getNodeKey());
        activeNodeInstanceDTO.setModelName(FlowModelUtil.getElementName(flowElement));
        activeNodeInstanceDTO.setProperties(flowElement.getProperties());

        return activeNodeInstanceDTO;
    }
}
