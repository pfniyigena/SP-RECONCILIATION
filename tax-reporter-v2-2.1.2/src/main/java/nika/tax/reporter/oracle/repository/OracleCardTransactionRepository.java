package nika.tax.reporter.oracle.repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import nika.tax.reporter.oracle.dto.CardTransactionDto;

@Repository
public class OracleCardTransactionRepository {

	private final JdbcTemplate oracleJdbcTemplate;

	public OracleCardTransactionRepository(@Qualifier("oracleJdbcTemplate")  JdbcTemplate oracleJdbcTemplate) {
		this.oracleJdbcTemplate = oracleJdbcTemplate;

	}

	public List<CardTransactionDto> findTodayTransaction(LocalDateTime start, LocalDateTime end) {
		String formattedStartDate = start.format(
		    DateTimeFormatter.ofPattern("dd/MM/yyyy")
		);
		String formattedEndDate = end.format(
			    DateTimeFormatter.ofPattern("dd/MM/yyyy")
			);

		return findAllForMaintenace(formattedStartDate, formattedEndDate);
	}

	public List<CardTransactionDto> findAllForMaintenace(String dateFrom, String dateTo) {

		String sql = """
				SELECT
				    TO_CHAR(txs.DATE_TRN, 'DD/MM/YYYY') || ' ' || txs.TIME_TRN AS date_time_txs,
				    '[' || cl.ID_FIRM || '] ' || cl.NAME_FIRM AS client_name,
				    txs.CARD_NUMBER AS card_number,
				    dcrd.HOLDER_CARD AS plate_number,
				    '[' || trm.ID_EMITENT || '] ' || trm.NAME_TERMINAL AS pos_name,
				    txs.NUMBER_TERMINAL AS pos_number,
				    tadr.NAME_TO AS address_name,
				    tadr.ADDRESS_TO AS address_description,
				    srv.NAME_SERVICES AS service_name,
				    txs.SUM_FOR_WHAT AS quantity,
				    txs.PRICE_RECALCULATION AS unit_price,
				    txs.SUM_IN_POS_CURRENCY AS total_amount,
				    txs.RFID_NUM AS tag_number,
				    trm.ID_EMITENT AS pos_id,
				    txs.ID_SERVICES_FOR_WHAT AS service_id,
				    txs.ID_CLIENT AS client_id,
				    txs.ID_TO AS address_id,
				    txs.TRN_GUID AS trn_guid,
				    txs.COMMENTS AS sap_reference
				FROM v_ecfil139 txs
				JOIN v_ecfil002 cl
				    ON txs.ID_CLIENT = cl.ID_FIRM
				JOIN V_ECFIL030 trm
				    ON txs.NUMBER_TERMINAL = trm.NUMBER_TERMINAL
				JOIN V_ECFIL037 tadr
				    ON txs.ID_TO = tadr.ID_TO
				JOIN V_ECFIL001 srv
				    ON txs.ID_SERVICES_FOR_WHAT = srv.ID_SERVICES
				LEFT JOIN oc_mifare_card mcrd
				    ON txs.CARD_NUMBER = mcrd.CARD_NUM
				LEFT JOIN V_ECFIL012 dcrd
				    ON txs.CARD_NUMBER = dcrd.CARD_NUMBER
				WHERE txs.REASON_CHANGE IN (11, 24, 25)
				AND txs.DATE_TRN >= TO_DATE(?, 'DD/MM/YYYY')
				  AND txs.DATE_TRN <= TO_DATE(?, 'DD/MM/YYYY')
				""";
		oracleJdbcTemplate.setFetchSize(1000);
		return oracleJdbcTemplate.query(sql,
				(rs, rowNum) -> new CardTransactionDto(rs.getString("date_time_txs"), rs.getString("client_name"),
						rs.getString("card_number"), rs.getString("plate_number"), rs.getString("pos_name"),
						rs.getInt("pos_number"), rs.getString("address_name"), rs.getString("address_description"),
						rs.getString("service_name"), rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"),
						rs.getBigDecimal("total_amount"), rs.getString("tag_number"), rs.getInt("pos_id"),
						rs.getInt("service_id"), rs.getInt("client_id"), rs.getInt("address_id"),
						rs.getString("trn_guid"),
						rs.getString("sap_reference")),
				dateFrom, dateTo);
	}

}
