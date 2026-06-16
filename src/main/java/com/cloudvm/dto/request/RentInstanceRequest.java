package com.cloudvm.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * Request body cho API thuê máy ảo.
 * POST /api/instances/rent
 *
 * userId không nhận từ client — sẽ được extract từ JWT token trong controller.
 */
@Getter
@Setter
public class RentInstanceRequest {

    @NotNull(message = "Package ID không được để trống")
    @Positive(message = "Package ID phải là số dương")
    private Integer packageId;
}
