package nika.tax.reporter.oracle.repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import nika.tax.reporter.oracle.dto.CustomerDepositDto;

@Repository
public class OracleCustomerDepositRepository {

	private final JdbcTemplate oracleJdbcTemplate;

	public OracleCustomerDepositRepository(@Qualifier("oracleJdbcTemplate") JdbcTemplate oracleJdbcTemplate) {
		this.oracleJdbcTemplate = oracleJdbcTemplate;

	}

	public List<CustomerDepositDto> findTodayTransaction(LocalDateTime start, LocalDateTime end) {
		String formattedStartDate = start.format(
		    DateTimeFormatter.ofPattern("dd/MM/yyyy")
		);
		String formattedEndDate = end.format(
			    DateTimeFormatter.ofPattern("dd/MM/yyyy")
			);

		return findAllForMaintenace(formattedStartDate, formattedEndDate);
	}

	public List<CustomerDepositDto> findAllForMaintenace(String dateFrom, String dateTo) {

		String sql = """
				SELECT
				    TO_CHAR(txs.DATE_TRN, 'DD/MM/YYYY') || ' ' || txs.TIME_TRN AS date_time_txs,
				    '[' || cl.ID_FIRM || '] ' || cl.NAME_FIRM AS client_name,
				    srv.NAME_SERVICES AS service_name, 
				    txs.SUM_THAN_REAL AS total_amount,
				    txs.ID_SERVICES_FOR_WHAT AS service_id,
				    txs.ID_CLIENT AS client_id,
				    txs.TRN_GUID AS trn_guid,
				    txs.COMMENTS AS sap_reference
				FROM v_ecfil139 txs
				LEFT JOIN v_ecfil002 cl
				    ON txs.ID_CLIENT = cl.ID_FIRM
				LEFT JOIN V_ECFIL001 srv
				    ON txs.ID_SERVICES_FOR_WHAT = srv.ID_SERVICES
				WHERE txs.REASON_CHANGE IN (1)
				AND TRIM(txs.COMMENTS) IS NOT NULL
				AND txs.DATE_TRN >= TO_DATE(?, 'DD/MM/YYYY')
				  AND txs.DATE_TRN <= TO_DATE(?, 'DD/MM/YYYY')
				""";
		oracleJdbcTemplate.setFetchSize(1000);
		return oracleJdbcTemplate.query(sql,
				(rs, rowNum) -> new CustomerDepositDto(rs.getString("date_time_txs"), rs.getString("client_name"),
						rs.getString("service_name"), 
						rs.getBigDecimal("total_amount"),
						rs.getInt("service_id"), rs.getInt("client_id"),
						rs.getString("trn_guid"),
						rs.getString("sap_reference")),
				dateFrom, dateTo);
	}

}
