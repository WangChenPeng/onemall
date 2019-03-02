package cn.iocoder.mall.admin.service;

import cn.iocoder.common.framework.constant.SysErrorCodeEnum;
import cn.iocoder.common.framework.dataobject.BaseDO;
import cn.iocoder.common.framework.util.ServiceExceptionUtil;
import cn.iocoder.common.framework.vo.CommonResult;
import cn.iocoder.mall.admin.api.AdminService;
import cn.iocoder.mall.admin.api.bo.AdminBO;
import cn.iocoder.mall.admin.api.bo.AdminPageBO;
import cn.iocoder.mall.admin.api.constant.AdminErrorCodeEnum;
import cn.iocoder.mall.admin.api.dto.AdminAddDTO;
import cn.iocoder.mall.admin.api.dto.AdminPageDTO;
import cn.iocoder.mall.admin.api.dto.AdminUpdateDTO;
import cn.iocoder.mall.admin.convert.AdminConvert;
import cn.iocoder.mall.admin.dao.AdminMapper;
import cn.iocoder.mall.admin.dao.AdminRoleMapper;
import cn.iocoder.mall.admin.dataobject.AdminDO;
import cn.iocoder.mall.admin.dataobject.AdminRoleDO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.util.Date;
import java.util.List;

@Service
@com.alibaba.dubbo.config.annotation.Service
public class AdminServiceImpl implements AdminService {

    @Autowired
    private AdminMapper adminMapper;
    @Autowired
    private AdminRoleMapper adminRoleMapper;
    @Autowired
    private OAuth2ServiceImpl oAuth2Service;

    public CommonResult<AdminDO> validAdmin(String username, String password) {
        AdminDO admin = adminMapper.selectByUsername(username);
        // 账号不存在
        if (admin == null) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_USERNAME_NOT_REGISTERED.getCode());
        }
        // 密码不正确
        if (encodePassword(password).equals(admin.getPassword())) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_PASSWORD_ERROR.getCode());
        }
        // 账号被禁用
        if (AdminDO.STATUS_DISABLE.equals(admin.getStatus())) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_IS_DISABLE.getCode());
        }
        // 校验成功，返回管理员。并且，去掉一些非关键字段，考虑安全性。
        admin.setPassword(null);
        admin.setStatus(null);
        return CommonResult.success(admin);
    }

    public List<AdminRoleDO> getAdminRoles(Integer adminId) {
        return adminRoleMapper.selectByAdminId(adminId);
    }

    @Override
    public CommonResult<AdminPageBO> getAdminPage(AdminPageDTO adminPageDTO) {
        AdminPageBO adminPage = new AdminPageBO();
        // 查询分页数据
        int offset = adminPageDTO.getPageNo() * adminPageDTO.getPageSize();
        adminPage.setAdmins(AdminConvert.INSTANCE.convert(adminMapper.selectListByNicknameLike(adminPageDTO.getNickname(),
                offset, adminPageDTO.getPageSize())));
        // 查询分页总数
        adminPage.setCount(adminMapper.selectCountByNicknameLike(adminPageDTO.getNickname()));
        return CommonResult.success(adminPage);
    }

    @Override
    public CommonResult<AdminBO> addAdmin(Integer adminId, AdminAddDTO adminAddDTO) {
        // 校验账号唯一
        if (adminMapper.selectByUsername(adminAddDTO.getUsername()) != null) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_USERNAME_EXISTS.getCode());
        }
        // 保存到数据库
        AdminDO admin = AdminConvert.INSTANCE.convert(adminAddDTO)
                .setPassword(encodePassword(adminAddDTO.getPassword())) // 加密密码
                .setStatus(AdminDO.STATUS_ENABLE);
        admin.setCreateTime(new Date()).setDeleted(BaseDO.DELETED_NO);
        adminMapper.insert(admin);
        // TODO 插入操作日志
        // 返回成功
        return CommonResult.success(AdminConvert.INSTANCE.convert(admin));
    }

    @Override
    public CommonResult<Boolean> updateAdmin(Integer adminId, AdminUpdateDTO adminUpdateDTO) {
        // 校验账号存在
        if (adminMapper.selectById(adminUpdateDTO.getId()) == null) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_USERNAME_NOT_REGISTERED.getCode());
        }
        // 校验账号唯一
        AdminDO usernameAdmin = adminMapper.selectByUsername(adminUpdateDTO.getUsername());
        if (usernameAdmin != null && !usernameAdmin.getId().equals(adminUpdateDTO.getId())) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_USERNAME_EXISTS.getCode());
        }
        // 更新到数据库
        AdminDO updateAdmin = AdminConvert.INSTANCE.convert(adminUpdateDTO);
        adminMapper.update(updateAdmin);
        // TODO 插入操作日志
        // 返回成功
        return CommonResult.success(true);
    }

    @Override
    @Transactional
    public CommonResult<Boolean> updateAdminStatus(Integer adminId, Integer updateAdminId, Integer status) {
        // 校验参数
        if (!isValidStatus(status)) {
            return CommonResult.error(SysErrorCodeEnum.VALIDATION_REQUEST_PARAM_ERROR.getCode(), "变更状态必须是开启（1）或关闭（2）"); // TODO 有点搓
        }
        // 校验账号存在
        AdminDO admin = adminMapper.selectById(updateAdminId);
        if (admin == null) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_USERNAME_NOT_REGISTERED.getCode());
        }
        // 如果状态相同，则返回错误
        if (status.equals(admin.getStatus())) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_STATUS_EQUALS.getCode());
        }
        // 更新管理员状态
        AdminDO updateAdmin = new AdminDO().setId(updateAdminId).setStatus(status);
        adminMapper.update(updateAdmin);
        // 如果是关闭管理员，则标记 token 失效。否则，管理员还可以继续蹦跶
        if (AdminDO.STATUS_DISABLE.equals(status)) {
            oAuth2Service.removeToken(updateAdminId);
        }
        // TODO 插入操作日志
        // 返回成功
        return CommonResult.success(true);
    }

    @Override
    @Transactional
    public CommonResult<Boolean> deleteAdmin(Integer adminId, Integer updateAdminId) {
        // 校验账号存在
        AdminDO admin = adminMapper.selectById(updateAdminId);
        if (admin == null) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_USERNAME_NOT_REGISTERED.getCode());
        }
        if (AdminDO.STATUS_ENABLE.equals(admin.getStatus())) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_DELETE_ONLY_DISABLE.getCode());
        }
        // 只有禁用的账号才可以删除
        AdminDO updateAdmin = new AdminDO().setId(updateAdminId);
        updateAdmin.setDeleted(BaseDO.DELETED_YES);
        adminMapper.update(updateAdmin);
        // 标记删除 AdminRole
        adminRoleMapper.updateToDeletedByAdminId(updateAdminId);
        // TODO 插入操作日志
        // 返回成功
        return CommonResult.success(true);
    }

    private String encodePassword(String password) {
        return DigestUtils.md5DigestAsHex(password.getBytes());
    }

    private boolean isValidStatus(Integer status) {
        return AdminDO.STATUS_ENABLE.equals(status)
                || AdminDO.STATUS_DISABLE.equals(status);
    }
}