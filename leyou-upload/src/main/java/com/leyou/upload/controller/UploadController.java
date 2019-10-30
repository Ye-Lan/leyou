package com.leyou.upload.controller;

import com.leyou.upload.service.UploadService;
import com.leyou.upload.service.impl.UploadServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;

@RestController
@RequestMapping("upload")
public class UploadController {

    @Resource(name = "uploadServiceImpl")
    private UploadService uploadService;

    @PostMapping("image")
    public ResponseEntity<String> uploadImage(@RequestParam("file") MultipartFile file){
        String url = this.uploadService.upload(file);
        if(StringUtils.isBlank(url)){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return ResponseEntity.ok(url);
    }

}
