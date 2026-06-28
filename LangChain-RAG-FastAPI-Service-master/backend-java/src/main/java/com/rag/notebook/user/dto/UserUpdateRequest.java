package com.rag.notebook.user.dto;

import lombok.Data;

@Data
public class UserUpdateRequest {

    private String username;
    private String telephone;
    private String avatar;
    private Integer gender;
    private String bio;
}
