package cat.iesmanacor.core.dto.gestib;

import lombok.Data;

public @Data class SubmateriaDto {
    private Long idsubmateria;
    private String gestibIdentificador;
    private String gestibNom;
    private String gestibNomCurt;
    private String gestibCurs;
}
