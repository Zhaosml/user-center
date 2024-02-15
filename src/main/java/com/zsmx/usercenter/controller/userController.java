package com.zsmx.usercenter.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zsmx.usercenter.common.BaseResponse;
import com.zsmx.usercenter.common.ErrorCode;
import com.zsmx.usercenter.common.ResultUtils;
import com.zsmx.usercenter.exception.BusinessException;
import com.zsmx.usercenter.model.User;
import com.zsmx.usercenter.model.request.UserLoginRequest;
import com.zsmx.usercenter.model.request.UserRegisterRequest;
import com.zsmx.usercenter.service.UserService;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.zsmx.usercenter.constant.UserConstant.USER_LOGIN_STATE;


/**
 *
 * 用户接口
 *
 *
 * @author xiaopang
 */
@RestController
@RequestMapping("/user")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true")
@Slf4j
public class userController {

    @Autowired
    private UserService userService;

    @Resource
    private RedisTemplate<String,Object> redisTemplate;

    /**
     * 用户注册
     *
     * @param userRegisterRequest
     * @return
     */
    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        // 校验 账户、密码、校验密码 是否为空
        if (userRegisterRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        String planetCode = userRegisterRequest.getPlanetCode();
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword, planetCode)) {
            return null;
        }
        long result = userService.userRegister(userAccount, userPassword, checkPassword, planetCode);
        return ResultUtils.success(result);
    }

    /**
     * 用户登录
     *
     * @param userLoginRequest
     * @param request
     * @return
     */
    @PostMapping("/login")
    public BaseResponse<User> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request) {
        if (userLoginRequest == null) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.userLogin(userAccount, userPassword, request);
        return ResultUtils.success(user);
    }

    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    @PostMapping("/logout")
    public BaseResponse<Integer> userLogout(HttpServletRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        int result = userService.userLogout(request);
        return ResultUtils.success(result);
    }

    /**
     * 获取当前用户
     *
     * @param request
     * @return
     */
    @GetMapping("/current")
    public BaseResponse<User> getCurrentUser(HttpServletRequest request) {
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        long userId = currentUser.getId();
        // TODO 校验用户是否合法
        User user = userService.getById(userId);
        User safetyUser = userService.getSafetyUser(user);
        return ResultUtils.success(safetyUser);
    }

    // https://yupi.icu/

    @GetMapping("/search")
    public BaseResponse<List<User>> searchUsers(String username, HttpServletRequest request) {
        if (!userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH, "缺少管理员权限");
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        if (StringUtils.isNotBlank(username)) {
            queryWrapper.like("username", username);
        }
        List<User> userList = userService.list(queryWrapper);
        List<User> list = userList.stream().map(user -> userService.getSafetyUser(user)).collect(Collectors.toList());
        return ResultUtils.success(list);
    }

    @GetMapping("/recommend")
    public BaseResponse<Page<User>> recommendUsers(long pageNum,long pageSize,HttpServletRequest request) {
        //1.获取当前登录用户信息
        User loginUser = userService.getLoginUser(request);
        //2.将当前登录用户信息的id保存为redis的key
        String key = String.format("zsmx:user:recommend:%s", loginUser.getId());
        //3.redis String类型的操作
        ValueOperations valueOperations = redisTemplate.opsForValue();
        // 4.如果有缓存，直接读缓存
        Page<User> page = (Page<User>) valueOperations.get(key);
        //判空，如果不为空则返回成功
        if(page != null){
            return ResultUtils.success(page);
        }
        // 5.如果没缓存，查询数据库
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        page = userService.page(new Page<>(pageNum,pageSize),queryWrapper);
        //6.写缓存     30秒一次
        try {
            valueOperations.set(key,page,30000, TimeUnit.MICROSECONDS);
        }
        catch (Exception e){
            log.error("redis set key error", e);
        }
        return ResultUtils.success(page);
    }

    @PostMapping("/update")
    public BaseResponse<Integer> updateUser(@RequestBody User user,HttpServletRequest request){
        //1.校验参数是否为空
        if (user == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //2.校验权限        获取当前登录信息里的登录状态
        User loginUser = userService.getLoginUser(request);

        //3.触发更新    修改参数
        int result = userService.updateUser(user,loginUser);
        return ResultUtils.success(result);
    }

    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteUser(@RequestBody long id, HttpServletRequest request) {
        if (!userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH);
        }
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        boolean b = userService.removeById(id);
        return ResultUtils.success(b);
    }

       /**
     * 是否为管理员
     *
     * @param
     * @return
     */
//    private boolean isAdmin(HttpServletRequest request) {
//        // 仅管理员可查询
//        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
//        User user = (User) userObj;
//        return user != null && user.getUserRole() == ADMIN_ROLE;
//    }

    @GetMapping("/search/tags")
    public BaseResponse<List<User>> searchUserbyTags(@RequestParam(required = false) List<String> tagNameList){
        if (CollectionUtils.isEmpty(tagNameList)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        List<User> userList = userService.searchUsersByTags(tagNameList);
        return ResultUtils.success(userList);
    }





}
