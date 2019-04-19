/*
 * <<
 * Davinci
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *       http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * >>
 */

package edp.davinci.service.impl;

import edp.core.enums.HttpCodeEnum;
import edp.core.exception.NotFoundException;
import edp.core.exception.ServerException;
import edp.core.exception.UnAuthorizedExecption;
import edp.core.utils.TokenUtils;
import edp.davinci.common.service.CommonService;
import edp.davinci.core.common.ResultMap;
import edp.davinci.core.enums.LogNameEnum;
import edp.davinci.core.enums.UserPermissionEnum;
import edp.davinci.dao.*;
import edp.davinci.dto.dashboardDto.DashboardPortalCreate;
import edp.davinci.dto.dashboardDto.DashboardPortalUpdate;
import edp.davinci.dto.dashboardDto.PortalWithProject;
import edp.davinci.dto.projectDto.ProjectDetail;
import edp.davinci.dto.projectDto.ProjectPermission;
import edp.davinci.model.*;
import edp.davinci.service.DashboardPortalService;
import edp.davinci.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service("dashboardPortalService")
@Slf4j
public class DashboardPortalServiceImpl extends CommonService<DashboardPortal> implements DashboardPortalService {
    private static final Logger optLogger = LoggerFactory.getLogger(LogNameEnum.BUSINESS_OPERATION.getName());

    @Autowired
    private TokenUtils tokenUtils;

    @Autowired
    private DashboardPortalMapper dashboardPortalMapper;

    @Autowired
    private DashboardMapper dashboardMapper;

    @Autowired
    private ExcludePortalTeamMapper excludePortalTeamMapper;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private RelRolePortalMapper relRolePortalMapper;

    @Override
    public synchronized boolean isExist(String name, Long id, Long projectId) {
        Long portalId = dashboardPortalMapper.getByNameWithProjectId(name, projectId);
        if (null != id && null != portalId) {
            return id.longValue() != portalId.longValue();
        }
        return null != portalId && portalId.longValue() > 0L;
    }

    /**
     * 获取DashboardPortal列表
     *
     * @param projectId
     * @param user
     * @return
     */
    @Override
    public List<DashboardPortal> getDashboardPortals(Long projectId, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

        ProjectDetail projectDetail = null;
        try {
            projectDetail = projectService.getProjectDetail(projectId, user, false);
        } catch (NotFoundException e) {
            throw e;
        } catch (UnAuthorizedExecption e) {
            return null;
        }

        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);
        if (projectPermission.getVizPermission() < UserPermissionEnum.READ.getPermission()) {
            return null;
        }

        return dashboardPortalMapper.getByProject(projectId, user.getId());
    }

    /**
     * 新建DashboardPortal
     *
     * @param dashboardPortalCreate
     * @param user
     * @return
     */
    @Override
    @Transactional
    public DashboardPortal createDashboardPortal(DashboardPortalCreate dashboardPortalCreate, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {


        ProjectDetail projectDetail = projectService.getProjectDetail(dashboardPortalCreate.getProjectId(), user, false);
        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);

        //校验权限
        if (projectPermission.getVizPermission() < UserPermissionEnum.WRITE.getPermission()) {
            log.info("user {} have not permisson to create widget", user.getUsername());
            throw new UnAuthorizedExecption("you have not permission to create portal");
        }

        if (isExist(dashboardPortalCreate.getName(), null, dashboardPortalCreate.getProjectId())) {
            log.info("the dashboardPortal \"{}\" name is already taken", dashboardPortalCreate.getName());
            throw new ServerException("the dashboard portal name is already taken");
        }

        DashboardPortal dashboardPortal = new DashboardPortal().createdBy(user.getId());
        BeanUtils.copyProperties(dashboardPortalCreate, dashboardPortal);

        int insert = dashboardPortalMapper.insert(dashboardPortal);
        if (insert > 0) {
            optLogger.info("portal ({}) is created by user(:{})", dashboardPortal.toString(), user.getId());

            if (null != dashboardPortalCreate.getRoleIds() && dashboardPortalCreate.getRoleIds().size() > 0) {
                List<Role> roles = roleMapper.getRolesByIds(dashboardPortalCreate.getRoleIds());

                List<RelRolePortal> list = roles.stream()
                        .map(r -> new RelRolePortal(r.getId(), dashboardPortal.getId()).createdBy(user.getId()))
                        .collect(Collectors.toList());

                relRolePortalMapper.insertBatch(list);

                optLogger.info("portal ({}) limit role ({}) access", dashboardPortal.getId(), roles.stream().map(r -> r.getId()).collect(Collectors.toList()));
            }

            return dashboardPortal;
        } else {
            throw new ServerException("create dashboardPortal fail");
        }
    }


    /**
     * 更新DashboardPortal
     *
     * @param dashboardPortalUpdate
     * @param user
     * @param request
     * @return
     */
    @Override
    @Transactional
    public ResultMap updateDashboardPortal(DashboardPortalUpdate dashboardPortalUpdate, User user, HttpServletRequest request) {
        ResultMap resultMap = new ResultMap(tokenUtils);

        PortalWithProject portalWithProject = dashboardPortalMapper.getPortalWithProjectById(dashboardPortalUpdate.getId());
        if (null == portalWithProject) {
            return resultMap.failAndRefreshToken(request).message("dashboardPortal not found");
        }

        Project project = portalWithProject.getProject();
        if (null == project) {
            return resultMap.failAndRefreshToken(request).message("project not found");
        }

        //校验权限
        if (!allowWrite(project, user)) {
            log.info("user {} have not permisson to create widget", user.getUsername());
            return resultMap.failAndRefreshToken(request, HttpCodeEnum.UNAUTHORIZED).message("you have not permission to create widget");
        }

        if (isExist(dashboardPortalUpdate.getName(), dashboardPortalUpdate.getId(), project.getId())) {
            log.info("the dashboardPortal \"{}\" name is already taken", dashboardPortalUpdate.getName());
            return resultMap.failAndRefreshToken(request).message("the dashboardPortal name is already taken");
        }

        DashboardPortal dashboardPortal = new DashboardPortal();
        BeanUtils.copyProperties(dashboardPortalUpdate, dashboardPortal);
        dashboardPortal.setProjectId(project.getId());

        int update = dashboardPortalMapper.update(dashboardPortal);
        if (update > 0) {
            List<Long> excludeTeams = excludePortalTeamMapper.selectExcludeTeamsByPortalId(dashboardPortal.getId());
            excludeTeamForPortal(dashboardPortalUpdate.getTeamIds(), dashboardPortal.getId(), user.getId(), excludeTeams);
            return resultMap.successAndRefreshToken(request).payload(dashboardPortal);
        } else {
            return resultMap.failAndRefreshToken(request).message("update dashboardPortal fail");
        }
    }


    @Override
    public List<Long> getExcludeTeams(Long id) {
        return excludePortalTeamMapper.selectExcludeTeamsByPortalId(id);
    }

    @Transactional
    protected void excludeTeamForPortal(List<Long> teamIds, Long portalId, Long userId, List<Long> excludeTeams) {
        if (null != excludeTeams && excludeTeams.size() > 0) {
            //已存在排除项
            if (null != teamIds && teamIds.size() > 0) {
                List<Long> rmTeamIds = new ArrayList<>();

                //对比要修改的项，删除不在要修改项中的排除项
                excludeTeams.forEach(teamId -> {
                    if (teamId.longValue() > 0L && !teamIds.contains(teamId)) {
                        rmTeamIds.add(teamId);
                    }
                });
                if (rmTeamIds.size() > 0) {
                    excludePortalTeamMapper.deleteByPortalIdAndTeamIds(portalId, rmTeamIds);
                }
            } else {
                //删除所有要排除的项
                excludePortalTeamMapper.deleteByPortalId(portalId);
            }
        }

        //添加排除项
        if (null != teamIds && teamIds.size() > 0) {
            List<ExcludePortalTeam> list = new ArrayList<>();
            teamIds.forEach(tid -> {
                list.add(new ExcludePortalTeam(tid, portalId, userId));
            });
            if (list.size() > 0) {
                excludePortalTeamMapper.insertBatch(list);
            }
        }
    }


    /**
     * 删除DashboardPortal
     *
     * @param id
     * @param user
     * @param request
     * @return
     */
    @Override
    @Transactional
    public ResultMap deleteDashboardPortal(Long id, User user, HttpServletRequest request) {
        ResultMap resultMap = new ResultMap(tokenUtils);

        PortalWithProject portalWithProject = dashboardPortalMapper.getPortalWithProjectById(id);

        if (null == portalWithProject) {
            log.info("dashboardPortal (:{}) not found", id);
            return resultMap.failAndRefreshToken(request, HttpCodeEnum.UNAUTHORIZED).message("dashboardPortal not found");
        }

        //校验权限
        if (!allowDelete(portalWithProject.getProject(), user)) {
            log.info("user {} have not permisson to delete the dashboardPortal {}", user.getUsername(), id);
            return resultMap.failAndRefreshToken(request, HttpCodeEnum.UNAUTHORIZED).message("you have not permission to delete the dashboardPortal");
        }

        dashboardMapper.deleteByPortalId(id);
        int i = dashboardPortalMapper.deleteById(id);
        if (i > 0) {
            excludePortalTeamMapper.deleteByPortalId(id);
        }

        return resultMap.successAndRefreshToken(request);
    }
}
